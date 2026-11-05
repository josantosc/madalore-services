package br.com.mandalore.agendamentoconversa.adapter.out.orcamento;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.OrcarPedidoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.TabelaPrecoUseCase;

@SpringBootTest(properties = {
        "orcamento.enabled=true", "curadoria.enabled=true", "curadoria.user=u", "curadoria.password=p"})
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresTabelaPrecoAdapterIT {

    @Autowired
    private PostgresTabelaPrecoAdapter tabela;

    @Autowired
    private TabelaPrecoUseCase importacao;

    @Autowired
    private OrcarPedidoUseCase orcamento;

    private List<Orcamento.ItemPreco> original;

    @BeforeEach
    void guardar() {
        original = tabela.itens();
    }

    @AfterEach
    void restaurar() {
        tabela.substituir(original);
    }

    @Test
    void importacaoSubstituiATabelaInteira() {
        tabela.substituir(List.of(new Orcamento.ItemPreco("OLD", "Antigo", List.of(), BigDecimal.ONE, "")));

        int itens = importacao.importar("""
                codigo;nome;sinonimos;valor;prazo
                HMG;Hemograma completo;HMG|Hemograma;25,00;1 dia útil
                GLI;Glicose;;12,5;
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(itens).isEqualTo(2);
        assertThat(orcamento).isNotNull();
        assertThat(tabela.itens())
                .extracting(Orcamento.ItemPreco::codigo, Orcamento.ItemPreco::valor, Orcamento.ItemPreco::sinonimos)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GLI", new BigDecimal("12.50"), List.of()),
                        org.assertj.core.groups.Tuple.tuple("HMG", new BigDecimal("25.00"), List.of("HMG", "Hemograma")));
    }
}
