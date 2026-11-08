package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoOperacao;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ExecutarFulfillmentUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.service.ContextoCodec;

class WebhookControllerPrazoTest {

    private static final String CREDENCIAL = "Basic " + Base64.getEncoder()
            .encodeToString("u:s".getBytes(StandardCharsets.UTF_8));

    @Test
    void consultaLentaRespondeIndisponivelAntesDoPrazoDoDialogflow() throws Exception {
        MockMvc mvc = mvc(command -> {
            dormir(2000);
            return new ResultadoOperacao("RESULTADO_SUCESSO_IDENTIFICAR", Map.of(), command.contexto(), CodigoNegocio.SUCESSO);
        });

        long inicio = System.nanoTime();
        chamar(mvc, "acao.paciente.identificar")
                .andExpect(jsonPath("$.followupEventInput.name").value("RESULTADO_INDISPONIVEL"));

        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(1500);
    }

    @Test
    void consultaRapidaUsaAContaDaSessao() throws Exception {
        AtomicReference<String> conta = new AtomicReference<>();
        MockMvc mvc = mvc(command -> {
            conta.set(ClienteAtual.accountId());
            return new ResultadoOperacao("RESULTADO_SUCESSO_IDENTIFICAR", Map.of(), command.contexto(), CodigoNegocio.SUCESSO);
        });

        chamar(mvc, "acao.paciente.identificar")
                .andExpect(jsonPath("$.followupEventInput.name").value("RESULTADO_SUCESSO_IDENTIFICAR"));

        assertThat(conta.get()).isEqualTo("8");
    }

    @Test
    void escritaNaoECortadaPeloPrazo() throws Exception {
        MockMvc mvc = mvc(command -> {
            dormir(800);
            return new ResultadoOperacao("RESULTADO_SUCESSO_ESCRITA", Map.of(), command.contexto(), CodigoNegocio.SUCESSO);
        });

        chamar(mvc, "acao.agenda.agendar")
                .andExpect(jsonPath("$.followupEventInput.name").value("RESULTADO_SUCESSO_ESCRITA"));
    }

    private MockMvc mvc(ExecutarFulfillmentUseCase fulfillment) {
        WebhookController controller = new WebhookController(
                fulfillment,
                new ContextoCodec(new ObjectMapper().findAndRegisterModules()),
                Mockito.mock(FlowTelemetry.class),
                "u",
                "s",
                500
        );
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private org.springframework.test.web.servlet.ResultActions chamar(MockMvc mvc, String intent) throws Exception {
        String corpo = """
                {"session": "projects/p/agent/sessions/cw-8-9",
                 "queryResult": {"queryText": "x", "intent": {"displayName": "%s"}, "parameters": {}, "outputContexts": []}}
                """.formatted(intent);
        return mvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", CREDENCIAL)
                        .content(corpo))
                .andExpect(status().isOk());
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
