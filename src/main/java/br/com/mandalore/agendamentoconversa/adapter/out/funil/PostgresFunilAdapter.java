package br.com.mandalore.agendamentoconversa.adapter.out.funil;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.FunilPort;

@Component
@ConditionalOnProperty(name = {"funil.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PostgresFunilAdapter implements FunilPort {

    private static final String ABERTAS = "('NOVO', 'ORCAMENTO_ENVIADO', 'EM_NEGOCIACAO')";

    private static final String COLUNAS = """
            SELECT id, conta_id, conversa_id, origem, etapa, motivo_perda, valor, qtd_itens, responsavel,
                   proxima_acao, ultima_resposta_em, aberta_em, fechada_em, paciente
            FROM funil.oportunidade
            """;

    private static final String ABRIR = """
            INSERT INTO funil.oportunidade (conta_id, conversa_id, origem, etapa, valor, qtd_itens, proxima_acao)
            VALUES (:conta, :conversa, :origem, 'NOVO', :valor, :qtd, :proxima)
            ON CONFLICT (conta_id, conversa_id) WHERE etapa IN %s DO UPDATE SET
                valor = COALESCE(EXCLUDED.valor, funil.oportunidade.valor),
                qtd_itens = COALESCE(EXCLUDED.qtd_itens, funil.oportunidade.qtd_itens),
                atualizada_em = CURRENT_TIMESTAMP
            RETURNING id, (xmax = 0) AS nova
            """.formatted(ABERTAS);

    private static final String MUDAR = """
            UPDATE funil.oportunidade SET
                etapa = :para,
                motivo_perda = :motivo,
                proxima_acao = :proxima,
                responsavel = COALESCE(:responsavel, responsavel),
                fechada_em = CASE WHEN :fechada THEN CURRENT_TIMESTAMP END,
                retorno_avisado_em = CASE WHEN :fechada THEN retorno_avisado_em END,
                atualizada_em = CURRENT_TIMESTAMP
            WHERE id = :id AND etapa = :de
            """;

    private static final String EVENTO = """
            INSERT INTO funil.evento (oportunidade_id, etapa_anterior, etapa, usuario, detalhe)
            VALUES (:id, :de, :para, :usuario, :detalhe)
            """;

    private static final String AGENDADAS = """
            WITH ganhas AS (
                SELECT o.id, o.etapa FROM funil.oportunidade o
                WHERE o.etapa IN %s
                  AND EXISTS (SELECT 1 FROM observability.flow_event e
                              WHERE e.conversation_id = o.conversa_id
                                AND e.component = 'MANDALORE' AND e.stage LIKE 'fulfillment.%%'
                                AND e.event_name = 'RESULTADO_SUCESSO_ESCRITA' AND e.details->>'escrita' = 'agendar'
                                AND e.created_at >= o.aberta_em)
                FOR UPDATE OF o
            ), mudou AS (
                UPDATE funil.oportunidade o SET etapa = 'AGENDADO', proxima_acao = NULL,
                       fechada_em = CURRENT_TIMESTAMP, atualizada_em = CURRENT_TIMESTAMP
                FROM ganhas g WHERE o.id = g.id
                RETURNING o.id, g.etapa AS anterior
            )
            INSERT INTO funil.evento (oportunidade_id, etapa_anterior, etapa, usuario, detalhe)
            SELECT id, anterior, 'AGENDADO', 'mandalore', 'Agendamento pelo assistente' FROM mudou
            """.formatted(ABERTAS);

    private static final String SEM_RESPOSTA = """
            WITH perdidas AS (
                SELECT id, etapa FROM funil.oportunidade
                WHERE etapa IN %s AND ultima_resposta_em < :limite
                FOR UPDATE
            ), mudou AS (
                UPDATE funil.oportunidade o SET etapa = 'PERDIDO', motivo_perda = 'SEM_RESPOSTA', proxima_acao = NULL,
                       fechada_em = CURRENT_TIMESTAMP, atualizada_em = CURRENT_TIMESTAMP
                FROM perdidas p WHERE o.id = p.id
                RETURNING o.id, p.etapa AS anterior
            )
            INSERT INTO funil.evento (oportunidade_id, etapa_anterior, etapa, usuario, detalhe)
            SELECT id, anterior, 'PERDIDO', 'mandalore', 'Sem resposta do paciente' FROM mudou
            """.formatted(ABERTAS);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transacao;

    public PostgresFunilAdapter(
            @Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Qualifier("observabilityDataSource") DataSource dataSource
    ) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public long abrir(String conta, String conversa, Funil.Origem origem, BigDecimal valor, Integer qtdItens,
                      LocalDate proximaAcao, String usuario) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("conta", conta)
                .addValue("conversa", conversa)
                .addValue("origem", origem.name())
                .addValue("valor", valor)
                .addValue("qtd", qtdItens)
                .addValue("proxima", data(proximaAcao));
        Long id = transacao.execute(status -> {
            Aberta aberta = jdbc.queryForObject(ABRIR, params, (rs, i) -> new Aberta(rs.getLong("id"), rs.getBoolean("nova")));
            if (aberta.nova()) {
                evento(aberta.id(), null, Funil.Etapa.NOVO, usuario, origem.name());
            }
            return aberta.id();
        });
        return id;
    }

    private record Aberta(long id, boolean nova) {
    }

    @Override
    public Optional<Funil.Oportunidade> aberta(String conta, String conversa) {
        return jdbc.query(COLUNAS + " WHERE conta_id = :conta AND conversa_id = :conversa AND etapa IN " + ABERTAS,
                new MapSqlParameterSource().addValue("conta", conta).addValue("conversa", conversa),
                PostgresFunilAdapter::oportunidade).stream().findFirst();
    }

    @Override
    public Optional<Funil.Oportunidade> buscar(long id) {
        return jdbc.query(COLUNAS + " WHERE id = :id", new MapSqlParameterSource("id", id),
                PostgresFunilAdapter::oportunidade).stream().findFirst();
    }

    @Override
    public boolean mudar(long id, Funil.Etapa de, Funil.Etapa para, Funil.MotivoPerda motivo, LocalDate proximaAcao,
                         String responsavel, String usuario) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("de", de.name())
                .addValue("para", para.name())
                .addValue("motivo", motivo == null ? null : motivo.name())
                .addValue("proxima", para.aberta() ? data(proximaAcao) : null)
                .addValue("responsavel", responsavel == null || responsavel.isBlank() ? null : limitar(responsavel, 200))
                .addValue("fechada", !para.aberta());
        try {
            return Boolean.TRUE.equals(transacao.execute(status -> {
                if (jdbc.update(MUDAR, params) == 0) {
                    return false;
                }
                evento(id, de, para, usuario, motivo == null ? "" : motivo.name());
                return true;
            }));
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("Já existe outra oportunidade aberta nesta conversa");
        }
    }

    @Override
    public void proximaAcao(long id, LocalDate data, String usuario) {
        jdbc.update("""
                UPDATE funil.oportunidade SET proxima_acao = :proxima, atualizada_em = CURRENT_TIMESTAMP
                WHERE id = :id AND etapa IN %s
                """.formatted(ABERTAS), new MapSqlParameterSource().addValue("id", id).addValue("proxima", data(data)));
    }

    @Override
    public void respondeu(long id) {
        jdbc.update("""
                UPDATE funil.oportunidade SET ultima_resposta_em = CURRENT_TIMESTAMP, atualizada_em = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource("id", id));
    }

    @Override
    public boolean valor(long id, BigDecimal valor, Integer qtdItens, String usuario) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("valor", valor)
                .addValue("qtd", qtdItens)
                .addValue("usuario", limitar(usuario == null || usuario.isBlank() ? "mandalore" : usuario, 200))
                .addValue("detalhe", "VALOR " + (valor == null ? "-" : valor.toPlainString())
                        + (qtdItens == null ? "" : " EXAMES " + qtdItens));
        return Boolean.TRUE.equals(transacao.execute(status -> {
            if (jdbc.update("""
                    UPDATE funil.oportunidade SET valor = :valor, qtd_itens = COALESCE(:qtd, qtd_itens),
                        atualizada_em = CURRENT_TIMESTAMP
                    WHERE id = :id
                    """, params) == 0) {
                return false;
            }
            jdbc.update("""
                    INSERT INTO funil.evento (oportunidade_id, etapa_anterior, etapa, usuario, detalhe)
                    SELECT id, etapa, etapa, :usuario, :detalhe FROM funil.oportunidade WHERE id = :id
                    """, params);
            return true;
        }));
    }

    @Override
    public void contato(long id, String paciente, String responsavel) {
        jdbc.update("""
                UPDATE funil.oportunidade SET
                    paciente = COALESCE(:paciente, paciente),
                    responsavel = COALESCE(:responsavel, responsavel),
                    atualizada_em = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("paciente", paciente == null || paciente.isBlank() ? null : limitar(paciente.trim(), 200))
                .addValue("responsavel", responsavel == null || responsavel.isBlank() ? null : limitar(responsavel.trim(), 200)));
    }

    @Override
    public List<Funil.Oportunidade> listar(Set<String> contas, Instant fechadasDesde) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("desde", OffsetDateTime.ofInstant(fechadasDesde, ZoneOffset.UTC))
                .addValue("contas", contas.isEmpty() ? List.of("") : List.copyOf(contas))
                .addValue("todas", contas.isEmpty());
        return jdbc.query(COLUNAS + """
                WHERE (:todas OR conta_id IN (:contas))
                  AND (etapa IN %s OR fechada_em >= :desde)
                ORDER BY proxima_acao NULLS LAST, aberta_em
                """.formatted(ABERTAS), params, PostgresFunilAdapter::oportunidade);
    }

    @Override
    public List<Funil.Oportunidade> retornosPendentes(LocalDate dia) {
        return jdbc.query(COLUNAS + """
                WHERE etapa IN %s AND proxima_acao <= :dia
                  AND (retorno_avisado_em IS NULL OR retorno_avisado_em < :dia)
                ORDER BY proxima_acao, id
                """.formatted(ABERTAS), new MapSqlParameterSource("dia", data(dia)), PostgresFunilAdapter::oportunidade);
    }

    @Override
    public void retornoAvisado(long id, LocalDate dia) {
        jdbc.update("UPDATE funil.oportunidade SET retorno_avisado_em = :dia WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("dia", data(dia)));
    }

    @Override
    public int fecharAgendadasPelaTrilha() {
        return jdbc.update(AGENDADAS, new MapSqlParameterSource());
    }

    @Override
    public int fecharSemResposta(Instant limite) {
        return jdbc.update(SEM_RESPOSTA,
                new MapSqlParameterSource("limite", OffsetDateTime.ofInstant(limite, ZoneOffset.UTC)));
    }

    private void evento(long id, Funil.Etapa de, Funil.Etapa para, String usuario, String detalhe) {
        jdbc.update(EVENTO, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("de", de == null ? null : de.name())
                .addValue("para", para.name())
                .addValue("usuario", limitar(usuario == null || usuario.isBlank() ? "mandalore" : usuario, 200))
                .addValue("detalhe", limitar(detalhe, 500)));
    }

    private static Funil.Oportunidade oportunidade(ResultSet rs, int linha) throws SQLException {
        String motivo = rs.getString("motivo_perda");
        Number qtd = (Number) rs.getObject("qtd_itens");
        Date proxima = rs.getDate("proxima_acao");
        return new Funil.Oportunidade(
                rs.getLong("id"),
                rs.getString("conta_id"),
                rs.getString("conversa_id"),
                Funil.Origem.valueOf(rs.getString("origem")),
                Funil.Etapa.valueOf(rs.getString("etapa")),
                motivo == null ? null : Funil.MotivoPerda.valueOf(motivo),
                rs.getBigDecimal("valor"),
                qtd == null ? null : qtd.intValue(),
                rs.getString("responsavel"),
                proxima == null ? null : proxima.toLocalDate(),
                instante(rs.getTimestamp("ultima_resposta_em")),
                instante(rs.getTimestamp("aberta_em")),
                instante(rs.getTimestamp("fechada_em")),
                rs.getString("paciente"));
    }

    private static Instant instante(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Date data(LocalDate data) {
        return data == null ? null : Date.valueOf(data);
    }

    private static String limitar(String texto, int limite) {
        return texto.length() <= limite ? texto : texto.substring(0, limite);
    }
}
