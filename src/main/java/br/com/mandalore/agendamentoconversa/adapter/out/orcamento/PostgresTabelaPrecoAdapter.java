package br.com.mandalore.agendamentoconversa.adapter.out.orcamento;

import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.TabelaPrecoPort;

@Component
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PostgresTabelaPrecoAdapter implements TabelaPrecoPort {

    private static final String ITENS = """
            SELECT codigo, nome, sinonimos, valor, prazo
            FROM orcamento.item_preco
            ORDER BY nome
            """;

    private static final String INSERIR = """
            INSERT INTO orcamento.item_preco (codigo, nome, sinonimos, valor, prazo)
            VALUES (:codigo, :nome, :sinonimos, :valor, :prazo)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transacao;

    public PostgresTabelaPrecoAdapter(
            @Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Qualifier("observabilityDataSource") DataSource dataSource
    ) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public List<Orcamento.ItemPreco> itens() {
        return jdbc.query(ITENS, (rs, i) -> new Orcamento.ItemPreco(
                rs.getString("codigo"),
                rs.getString("nome"),
                Arrays.stream(rs.getString("sinonimos").split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                rs.getBigDecimal("valor"),
                rs.getString("prazo")));
    }

    @Override
    public void substituir(List<Orcamento.ItemPreco> itens) {
        SqlParameterSource[] linhas = itens.stream()
                .map(item -> new MapSqlParameterSource()
                        .addValue("codigo", item.codigo())
                        .addValue("nome", item.nome())
                        .addValue("sinonimos", String.join("|", item.sinonimos()))
                        .addValue("valor", item.valor())
                        .addValue("prazo", item.prazo()))
                .toArray(SqlParameterSource[]::new);
        transacao.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().update("DELETE FROM orcamento.item_preco");
            jdbc.batchUpdate(INSERIR, linhas);
        });
    }
}
