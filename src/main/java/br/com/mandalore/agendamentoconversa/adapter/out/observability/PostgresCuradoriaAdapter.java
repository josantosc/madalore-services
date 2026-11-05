package br.com.mandalore.agendamentoconversa.adapter.out.observability;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.CuradoriaPort;

@Component
@ConditionalOnProperty(name = {"curadoria.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PostgresCuradoriaAdapter implements CuradoriaPort {

    private static final String MENSAGENS = """
            WITH m AS (
                SELECT conversation_id, intent, created_at,
                       details->>'texto' AS texto,
                       (details->>'confianca')::numeric AS confianca,
                       COALESCE((details->>'fallback')::boolean, false) AS fallback
                FROM observability.flow_event
                WHERE component = 'DIALOGFLOW' AND stage = 'detect_intent' AND status = 'SUCCESS'
                  AND created_at > now() - make_interval(days => :dias)
            )
            """;

    private static final String PENDENTE = """
            WHERE COALESCE(m.texto, '') <> ''
              AND (m.fallback OR m.confianca < :limite)
              AND NOT EXISTS (
                  SELECT 1 FROM observability.curadoria_decisao d WHERE d.chave = lower(trim(m.texto))
              )
            """;

    private static final String FILA = MENSAGENS + """
            SELECT min(m.texto) AS texto,
                   count(*) AS vezes,
                   mode() WITHIN GROUP (ORDER BY m.intent) AS intent,
                   round(avg(m.confianca), 2) AS confianca,
                   bool_or(m.fallback) AS fallback,
                   max(m.created_at) AS ultima
            FROM m
            """ + PENDENTE + """
            GROUP BY lower(trim(m.texto))
            ORDER BY vezes DESC, ultima DESC
            LIMIT 200
            """;

    private static final String TOTAIS = MENSAGENS + """
            SELECT count(*) AS mensagens,
                   count(DISTINCT conversation_id) AS conversas,
                   count(*) FILTER (WHERE fallback) AS fallback,
                   count(*) FILTER (WHERE confianca < :limite) AS baixa,
                   count(DISTINCT conversation_id) FILTER (WHERE intent LIKE 'transbordo.%') AS transbordos,
                   (SELECT count(DISTINCT lower(trim(m.texto))) FROM m
            """ + PENDENTE + """
                   ) AS pendentes,
                   (SELECT count(*) FROM observability.curadoria_decisao
                     WHERE acao = 'ENSINAR' AND created_at > now() - make_interval(days => :dias)) AS ensinadas
            FROM m
            """;

    private static final String POR_DIA = MENSAGENS + """
            SELECT (created_at AT TIME ZONE 'America/Sao_Paulo')::date AS dia,
                   count(*) AS mensagens,
                   count(*) FILTER (WHERE fallback) AS fallback,
                   count(*) FILTER (WHERE confianca < :limite) AS baixa
            FROM m
            GROUP BY 1
            ORDER BY 1
            """;

    private static final String INTENTS = MENSAGENS + """
            SELECT COALESCE(NULLIF(intent, ''), '(sem intent)') AS intent,
                   count(*) AS vezes,
                   round(avg(confianca), 2) AS confianca
            FROM m
            GROUP BY 1
            ORDER BY vezes DESC
            LIMIT 15
            """;

    private static final String REGISTRAR = """
            INSERT INTO observability.curadoria_decisao (chave, texto, acao, intent, frase, usuario)
            VALUES (lower(trim(:texto)), :texto, :acao, :intent, :frase, :usuario)
            ON CONFLICT (chave) DO UPDATE SET
                acao = EXCLUDED.acao,
                intent = EXCLUDED.intent,
                frase = EXCLUDED.frase,
                usuario = EXCLUDED.usuario,
                created_at = CURRENT_TIMESTAMP
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresCuradoriaAdapter(@Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Curadoria.Indicadores indicadores(int dias, double limiteConfianca) {
        MapSqlParameterSource params = params(dias, limiteConfianca);
        List<Curadoria.Dia> porDia = jdbc.query(POR_DIA, params, (rs, i) -> new Curadoria.Dia(
                rs.getDate("dia").toLocalDate(),
                rs.getLong("mensagens"),
                rs.getLong("fallback"),
                rs.getLong("baixa")));
        List<Curadoria.IntentUso> intents = jdbc.query(INTENTS, params, (rs, i) -> new Curadoria.IntentUso(
                rs.getString("intent"),
                rs.getLong("vezes"),
                decimal(rs, "confianca")));
        return jdbc.queryForObject(TOTAIS, params, (rs, i) -> new Curadoria.Indicadores(
                rs.getLong("mensagens"),
                rs.getLong("conversas"),
                rs.getLong("fallback"),
                rs.getLong("baixa"),
                rs.getLong("transbordos"),
                rs.getLong("pendentes"),
                rs.getLong("ensinadas"),
                porDia,
                intents));
    }

    @Override
    public List<Curadoria.Frase> fila(int dias, double limiteConfianca) {
        return jdbc.query(FILA, params(dias, limiteConfianca), (rs, i) -> {
            Timestamp ultima = rs.getTimestamp("ultima");
            return new Curadoria.Frase(
                    rs.getString("texto"),
                    rs.getLong("vezes"),
                    rs.getString("intent"),
                    decimal(rs, "confianca"),
                    rs.getBoolean("fallback"),
                    ultima == null ? null : ultima.toInstant());
        });
    }

    @Override
    public void registrar(Curadoria.Decisao decisao) {
        jdbc.update(REGISTRAR, new MapSqlParameterSource()
                .addValue("texto", decisao.texto().trim())
                .addValue("acao", decisao.acao().name())
                .addValue("intent", decisao.intent())
                .addValue("frase", decisao.frase())
                .addValue("usuario", decisao.usuario()));
    }

    private static MapSqlParameterSource params(int dias, double limiteConfianca) {
        return new MapSqlParameterSource()
                .addValue("dias", dias)
                .addValue("limite", BigDecimal.valueOf(limiteConfianca));
    }

    private static Double decimal(ResultSet rs, String coluna) throws SQLException {
        BigDecimal valor = rs.getBigDecimal(coluna);
        return valor == null ? null : valor.doubleValue();
    }
}
