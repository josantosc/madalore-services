package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.mandalore.agendamentoconversa.application.domain.model.Anexo;
import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.SimularChatwootUseCase;

class SimuladorChatwootControllerTest {

    private SimularChatwootUseCase simular;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        simular = mock(SimularChatwootUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new SimuladorChatwootController(simular, "http://chatwoot.local/", "2")
        ).build();
    }

    @Test
    void transbordoApareceComoEmAtendimento() throws Exception {
        when(simular.enviar("ab12", "atendente")).thenReturn(new ConversaVisitante("27", "open", List.of(
                new ConversaVisitante.Mensagem(1, "atendente", true, "visitante", "contact"),
                new ConversaVisitante.Mensagem(2, "Vou chamar alguém da equipe.", false, "bot-agenda", "agent_bot")
        )));

        mvc.perform(post("/simulador/chatwoot/mensagem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"sessao\":\"ab12\",\"texto\":\"atendente\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("27"))
                .andExpect(jsonPath("$.emAtendimento").value(true))
                .andExpect(jsonPath("$.painelUrl").value("http://chatwoot.local/app/accounts/2/conversations/27"))
                .andExpect(jsonPath("$.mensagens[1].tipoRemetente").value("agent_bot"));
    }

    @Test
    void textoObrigatorio() throws Exception {
        mvc.perform(post("/simulador/chatwoot/mensagem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"sessao\":\"ab12\",\"texto\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fotoVaiComoAnexoComLegenda() throws Exception {
        when(simular.enviarArquivo(eq("ab12"), any(), eq("meu pedido"))).thenReturn(new ConversaVisitante("27", "pending", List.of(
                new ConversaVisitante.Mensagem(1, "meu pedido", true, "visitante", "contact",
                        List.of(new Anexo("image", "http://chatwoot.local/rails/pedido.jpg"))))));

        mvc.perform(multipart("/simulador/chatwoot/arquivo")
                        .file(new MockMultipartFile("arquivo", "pedido.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                        .param("sessao", "ab12")
                        .param("texto", "meu pedido"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensagens[0].anexos[0].tipo").value("image"));

        ArgumentCaptor<ConversaVisitante.Arquivo> arquivo = ArgumentCaptor.forClass(ConversaVisitante.Arquivo.class);
        verify(simular).enviarArquivo(eq("ab12"), arquivo.capture(), eq("meu pedido"));
        assertThat(arquivo.getValue().nome()).isEqualTo("pedido.jpg");
        assertThat(arquivo.getValue().tipo()).isEqualTo("image/jpeg");
        assertThat(arquivo.getValue().conteudo()).containsExactly(1, 2, 3);
    }

    @Test
    void arquivoQueNaoEhFotoNemPdfEhRecusado() throws Exception {
        mvc.perform(multipart("/simulador/chatwoot/arquivo")
                        .file(new MockMultipartFile("arquivo", "planilha.xlsx", "application/vnd.ms-excel", new byte[] {1}))
                        .param("sessao", "ab12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Envie uma foto ou um PDF"));
        verifyNoInteractions(simular);
    }

    @Test
    void falhaDoChatwootViraBadGateway() throws Exception {
        when(simular.ler("ab12")).thenThrow(new IllegalStateException("CHATWOOT_WEBSITE_TOKEN ausente"));

        mvc.perform(get("/simulador/chatwoot/conversa").param("sessao", "ab12"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.erro").value("Falha ao falar com o Chatwoot: CHATWOOT_WEBSITE_TOKEN ausente"));
    }
}
