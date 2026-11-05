package br.com.mandalore.agendamentoconversa.adapter.out.orcamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.OrcarPedidoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.TabelaPrecoUseCase;

@SpringBootTest(properties = {"orcamento.enabled=true", "precos.user=u", "precos.password=p"})
@AutoConfigureMockMvc
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresTabelaPrecoAdapterIT {

    private static final String LOGIN = "Basic " + java.util.Base64.getEncoder().encodeToString("u:p".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private PostgresTabelaPrecoAdapter tabela;

    @Autowired
    private TabelaPrecoUseCase precos;

    @Autowired
    private OrcarPedidoUseCase orcamento;

    @Autowired
    private MockMvc mvc;

    private List<Orcamento.ItemPreco> original;

    @BeforeEach
    void guardar() {
        original = tabela.itens();
    }

    @AfterEach
    void restaurar() {
        tabela.substituir(original, "teste");
    }

    @Test
    void importacaoSubstituiATabelaInteira() {
        tabela.substituir(List.of(new Orcamento.ItemPreco("OLD", "Antigo", List.of(), BigDecimal.ONE, "")), "teste");

        int itens = precos.importar("""
                codigo;nome;sinonimos;valor;prazo
                HMG;Hemograma completo;HMG|Hemograma;25,00;1 dia útil
                GLI;Glicose;;12,5;
                """.getBytes(StandardCharsets.UTF_8), "financeiro");

        assertThat(itens).isEqualTo(2);
        assertThat(orcamento).isNotNull();
        assertThat(tabela.itens())
                .extracting(Orcamento.ItemPreco::codigo, Orcamento.ItemPreco::valor,
                        Orcamento.ItemPreco::sinonimos, Orcamento.ItemPreco::atualizadoPor)
                .containsExactly(
                        Tuple.tuple("GLI", new BigDecimal("12.50"), List.of(), "financeiro"),
                        Tuple.tuple("HMG", new BigDecimal("25.00"), List.of("HMG", "Hemograma"), "financeiro"));
    }

    @Test
    void salvarAtualizaOuIncluiERemoverApaga() {
        tabela.substituir(List.of(new Orcamento.ItemPreco("HMG", "Hemograma", List.of(), BigDecimal.ZERO, "")), "teste");

        precos.salvar(new Orcamento.ItemPreco("HMG", "Hemograma completo", List.of("HMG"), new BigDecimal("25"), "1 dia"), "ana");
        precos.salvar(new Orcamento.ItemPreco("TSH", "TSH", List.of(), new BigDecimal("30.5"), ""), "ana");

        assertThat(tabela.itens())
                .extracting(Orcamento.ItemPreco::codigo, Orcamento.ItemPreco::nome, Orcamento.ItemPreco::valor,
                        Orcamento.ItemPreco::atualizadoPor)
                .containsExactly(
                        Tuple.tuple("HMG", "Hemograma completo", new BigDecimal("25.00"), "ana"),
                        Tuple.tuple("TSH", "TSH", new BigDecimal("30.50"), "ana"));
        assertThat(tabela.itens()).allSatisfy(item -> assertThat(item.atualizadoEm()).isNotNull());

        assertThat(precos.remover("TSH", "ana")).isTrue();
        assertThat(precos.remover("TSH", "ana")).isFalse();
        assertThat(tabela.itens()).extracting(Orcamento.ItemPreco::codigo).containsExactly("HMG");
    }

    @Test
    void telaExigeLoginESalvaComOUsuarioLogado() throws Exception {
        tabela.substituir(List.of(), "teste");

        mvc.perform(get("/precos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/precos").header("Authorization", LOGIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tabela de preços")));
        mvc.perform(put("/precos/api/itens/GLI").header("Authorization", LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Glicose\",\"sinonimos\":[\"Glicemia\"],\"valor\":12.5,\"prazo\":\"\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/precos/api/itens/GLI").header("Authorization", LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"\",\"valor\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value(containsString("Nome")));
        mvc.perform(get("/precos/api/itens").header("Authorization", LOGIN))
                .andExpect(jsonPath("$[0].codigo").value("GLI"))
                .andExpect(jsonPath("$[0].valor").value(12.5))
                .andExpect(jsonPath("$[0].atualizadoPor").value("u"));
        mvc.perform(get("/precos/api/exportar").header("Authorization", LOGIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GLI;Glicose;Glicemia;12,50;")));
        mvc.perform(delete("/precos/api/itens/NAOEXISTE").header("Authorization", LOGIN))
                .andExpect(status().isNotFound());
    }
}
