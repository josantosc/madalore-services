package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.SimularConversaUseCase;

class SimuladorControllerTest {

    private SimularConversaUseCase simular;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        simular = mock(SimularConversaUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new SimuladorController(simular)).build();
    }

    @Test
    void devolveRespostaComDetalhesDoTurno() throws Exception {
        when(simular.enviar("ab12", "atendente")).thenReturn(new RespostaDialogflow(
                "transbordo.atendente",
                "Vou chamar alguém da equipe.",
                "transbordo",
                Map.of("cpf", "00936398302"),
                List.of(new RespostaDialogflow.ContextoAtivo("menu", 2)),
                null
        ));

        mvc.perform(post("/simulador/mensagem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"sessao\":\"ab12\",\"texto\":\"atendente\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.texto").value("Vou chamar alguém da equipe."))
                .andExpect(jsonPath("$.intent").value("transbordo.atendente"))
                .andExpect(jsonPath("$.transbordo").value(true))
                .andExpect(jsonPath("$.parametros.cpf").value("00936398302"))
                .andExpect(jsonPath("$.contextos[0].nome").value("menu"))
                .andExpect(jsonPath("$.contextos[0].lifespan").value(2));
    }

    @Test
    void sessaoObrigatoria() throws Exception {
        mvc.perform(post("/simulador/mensagem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"texto\":\"oi\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void falhaDoDialogflowViraBadGateway() throws Exception {
        when(simular.enviar("ab12", "oi")).thenThrow(new IllegalStateException("DIALOGFLOW_PROJECT_ID ausente"));

        mvc.perform(post("/simulador/mensagem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"sessao\":\"ab12\",\"texto\":\"oi\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.erro").value("Falha ao consultar o Dialogflow: DIALOGFLOW_PROJECT_ID ausente"));
    }

    @Test
    void servePagina() throws Exception {
        mvc.perform(get("/simulador"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Simulador de conversa")));
    }
}
