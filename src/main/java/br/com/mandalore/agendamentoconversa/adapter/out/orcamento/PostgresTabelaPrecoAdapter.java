package br.com.mandalore.agendamentoconversa.adapter.out.orcamento;

import java.sql.Timestamp;
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
            SELECT codigo, nome, sinonimos, valor, prazo, updated_at, updated_by
            FROM orcamento.item_preco
            ORDER BY nome
            """;

    private static final String INSERIR = """
            INSERT INTO orcamento.item_preco (codigo, nome, sinonimos, valor, prazo, updated_by)
            VALUES (:codigo, :nome, :sinonimos, :valor, :prazo, :usuario)
            """;

    private static final String SALVAR = INSERIR + """
            ON CONFLICT (codigo) DO UPDATE SET
                nome = EXCLUDED.nome,
                sinonimos = EXCLUDED.sinonimos,
                valor = EXCLUDED.valor,
                prazo = EXCLUDED.prazo,
                updated_by = EXCLUDED.updated_by,
                updated_at = CURRENT_TIMESTAMP
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
        return jdbc.query(ITENS, (rs, i) -> {
            Timestamp atualizado = rs.getTimestamp("updated_at");
            return new Orcamento.ItemPreco(
                    rs.getString("codigo"),
                    rs.getString("nome"),
                    Arrays.stream(rs.getString("sinonimos").split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                    rs.getBigDecimal("valor"),
                    rs.getString("prazo"),
                    atualizado == null ? null : atualizado.toInstant(),
                    rs.getString("updated_by"));
        });
    }

    @Override
    public void substituir(List<Orcamento.ItemPreco> itens, String usuario) {
        SqlParameterSource[] linhas = itens.stream()
                .map(item -> parametros(item, usuario))
                .toArray(SqlParameterSource[]::new);
        transacao.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().update("DELETE FROM orcamento.item_preco");
            jdbc.batchUpdate(INSERIR, linhas);
        });
    }

    @Override
    public void salvar(Orcamento.ItemPreco item, String usuario) {
        jdbc.update(SALVAR, parametros(item, usuario));
    }

    @Override
    public boolean remover(String codigo) {
        return jdbc.update("DELETE FROM orcamento.item_preco WHERE codigo = :codigo",
                new MapSqlParameterSource("codigo", codigo)) > 0;
    }

    private static MapSqlParameterSource parametros(Orcamento.ItemPreco item, String usuario) {
        return new MapSqlParameterSource()
                .addValue("codigo", item.codigo())
                .addValue("nome", item.nome())
                .addValue("sinonimos", String.join("|", item.sinonimos()))
                .addValue("valor", item.valor())
                .addValue("prazo", item.prazo())
                .addValue("usuario", usuario);
    }
}
