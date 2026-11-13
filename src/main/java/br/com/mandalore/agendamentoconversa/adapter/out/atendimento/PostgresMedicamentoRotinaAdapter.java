package br.com.mandalore.agendamentoconversa.adapter.out.atendimento;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.MedicamentoRotinaPort;

@Component
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PostgresMedicamentoRotinaAdapter implements MedicamentoRotinaPort {

    static final String SISTEMA = "sistema";
    private static final int ATOR_MAX = 150;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transacao;

    public PostgresMedicamentoRotinaAdapter(@Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc,
                                            @Qualifier("observabilityDataSource") DataSource dataSource) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public boolean semearSeNova(String conta, List<Atendimento.MedicamentoRotina> padrao) {
        return Boolean.TRUE.equals(transacao.execute(status -> {
            int nova = jdbc.update("INSERT INTO atendimento.medicamento_rotina_conta (conta_id) VALUES (:conta) "
                    + "ON CONFLICT (conta_id) DO NOTHING", new MapSqlParameterSource("conta", conta));
            if (nova == 1) {
                inserirFaltantes(conta, padrao, SISTEMA);
            }
            return nova == 1;
        }));
    }

    @Override
    public int incluirFaltantes(String conta, List<Atendimento.MedicamentoRotina> itens, String ator) {
        Integer total = transacao.execute(status -> inserirFaltantes(conta, itens, ator));
        return total == null ? 0 : total;
    }

    private int inserirFaltantes(String conta, List<Atendimento.MedicamentoRotina> itens, String ator) {
        int total = 0;
        for (Atendimento.MedicamentoRotina item : itens) {
            total += jdbc.update("""
                    INSERT INTO atendimento.medicamento_rotina
                        (conta_id, ds_medicamento, ds_apresentacao, ds_posologia, ie_via_aplicacao, ie_uso_continuo,
                         atualizado_por)
                    VALUES (:conta, :medicamento, :apresentacao, :posologia, :via, :continuo, :ator)
                    ON CONFLICT (conta_id, (lower(btrim(ds_medicamento))), (lower(btrim(ds_apresentacao)))) DO NOTHING
                    """, parametros(conta, item, ator));
        }
        return total;
    }

    @Override
    public List<Atendimento.MedicamentoRotina> listar(String conta) {
        return jdbc.query("""
                SELECT id, ds_medicamento, ds_apresentacao, ds_posologia, ie_via_aplicacao, ie_uso_continuo
                FROM atendimento.medicamento_rotina
                WHERE conta_id = :conta
                ORDER BY lower(ds_medicamento), lower(ds_apresentacao), id
                """, new MapSqlParameterSource("conta", conta), (rs, i) -> new Atendimento.MedicamentoRotina(
                rs.getLong("id"), rs.getString("ds_medicamento"), rs.getString("ds_apresentacao"),
                rs.getString("ds_posologia"), rs.getString("ie_via_aplicacao"), rs.getBoolean("ie_uso_continuo")));
    }

    @Override
    public Atendimento.Operacao salvar(String conta, Atendimento.MedicamentoRotina item, String ator) {
        MapSqlParameterSource params = parametros(conta, item, ator);
        try {
            if (item.id() == null) {
                Long id = jdbc.queryForObject("""
                        INSERT INTO atendimento.medicamento_rotina
                            (conta_id, ds_medicamento, ds_apresentacao, ds_posologia, ie_via_aplicacao, ie_uso_continuo,
                             atualizado_por)
                        VALUES (:conta, :medicamento, :apresentacao, :posologia, :via, :continuo, :ator)
                        RETURNING id
                        """, params, Long.class);
                return Atendimento.Operacao.sucesso(id);
            }
            int alterados = jdbc.update("""
                    UPDATE atendimento.medicamento_rotina
                    SET ds_medicamento = :medicamento, ds_apresentacao = :apresentacao, ds_posologia = :posologia,
                        ie_via_aplicacao = :via, ie_uso_continuo = :continuo, atualizado_por = :ator,
                        atualizado_em = CURRENT_TIMESTAMP
                    WHERE id = :id AND conta_id = :conta
                    """, params.addValue("id", item.id()));
            return alterados == 1 ? Atendimento.Operacao.sucesso(item.id())
                    : Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        } catch (DuplicateKeyException ex) {
            return Atendimento.Operacao.falha(CodigoNegocio.MEDICAMENTO_DUPLICADO);
        }
    }

    @Override
    public boolean excluir(String conta, long id) {
        return jdbc.update("DELETE FROM atendimento.medicamento_rotina WHERE id = :id AND conta_id = :conta",
                new MapSqlParameterSource("conta", conta).addValue("id", id)) == 1;
    }

    private static MapSqlParameterSource parametros(String conta, Atendimento.MedicamentoRotina item, String ator) {
        String autor = ator == null || ator.isBlank() ? SISTEMA : ator.strip();
        return new MapSqlParameterSource("conta", conta)
                .addValue("medicamento", item.medicamento())
                .addValue("apresentacao", item.apresentacao())
                .addValue("posologia", item.posologia())
                .addValue("via", item.via())
                .addValue("continuo", item.usoContinuo())
                .addValue("ator", autor.length() > ATOR_MAX ? autor.substring(0, ATOR_MAX) : autor);
    }
}
