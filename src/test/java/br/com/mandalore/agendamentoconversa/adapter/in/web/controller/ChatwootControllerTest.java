package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties.Cliente;
import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberMensagemUseCase;

class ChatwootControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private ReceberMensagemUseCase useCase;
    private ChatwootController controller;

    @BeforeEach
    void setUp() {
        useCase = mock(ReceberMensagemUseCase.class);
        ClientesProperties clientes = new ClientesProperties(Map.of("2", new Cliente("tk-2", "segredo-2", null, null, null)));
        controller = new ChatwootController(useCase, "segredo-global", clientes);
    }

    @Test
    void contaCadastradaExigeOProprioSegredo() throws Exception {
        assertThat(controller.receber("segredo-global", null, null, corpo("2", "Channel::Whatsapp")).getStatusCode().value())
                .isEqualTo(401);
        assertThat(controller.receber("segredo-2", null, null, corpo("2", "Channel::Whatsapp")).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void segredoDeUmaContaNaoServeParaOutra() throws Exception {
        assertThat(controller.receber("segredo-2", null, null, corpo("7", "Channel::Whatsapp")).getStatusCode().value())
                .isEqualTo(401);
        verify(useCase, never()).receber(any());
    }

    @Test
    void contaSemCadastroUsaSegredoGlobal() throws Exception {
        assertThat(controller.receber("segredo-global", null, null, corpo("7", "Channel::WebWidget")).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void leOCanalDaConversa() throws Exception {
        controller.receber("segredo-2", null, null, corpo("2", "Channel::Instagram"));

        ArgumentCaptor<MensagemEntrada> mensagem = ArgumentCaptor.forClass(MensagemEntrada.class);
        verify(useCase).receber(mensagem.capture());
        assertThat(mensagem.getValue().canal()).isEqualTo(Canal.INSTAGRAM);
        assertThat(mensagem.getValue().accountId()).isEqualTo("2");
    }

    @Test
    void leFotoEPdfAnexados() throws Exception {
        JsonNode corpo = json.readTree("""
                {
                  "event": "message_created",
                  "message_type": "incoming",
                  "content": null,
                  "id": 11,
                  "account": {"id": 2},
                  "conversation": {"id": 99, "status": "pending", "channel": "Channel::Whatsapp"},
                  "attachments": [
                    {"file_type": "audio", "data_url": "https://chat/a.ogg"},
                    {"file_type": "file", "data_url": "https://chat/rails/blobs/pedido.PDF?disposition=inline"},
                    {"file_type": "image", "data_url": "https://chat/rails/blobs/foto.jpg"}
                  ]
                }
                """);

        controller.receber("segredo-2", null, null, corpo);

        ArgumentCaptor<MensagemEntrada> mensagem = ArgumentCaptor.forClass(MensagemEntrada.class);
        verify(useCase).receber(mensagem.capture());
        assertThat(mensagem.getValue().anexos()).hasSize(3);
        assertThat(mensagem.getValue().documento()).hasValueSatisfying(
                anexo -> assertThat(anexo.url()).endsWith("pedido.PDF?disposition=inline"));
    }

    @Test
    void respostaDoAtendenteComConversaAbertaVaiParaOFunil() throws Exception {
        FunilUseCase funil = mock(FunilUseCase.class);
        controller = new ChatwootController(useCase, "segredo-global", new ClientesProperties(Map.of()),
                (Supplier<FunilUseCase>) () -> funil);

        controller.receber("segredo-global", null, null, json.readTree("""
                {"event": "message_created", "message_type": "outgoing", "private": false, "id": 12,
                 "account": {"id": 1}, "sender": {"id": 3, "name": "Ana", "type": "user"},
                 "conversation": {"id": 7, "status": "open"}}
                """));

        verify(funil).registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null));
    }

    @Test
    void respostaDoBotNotaPrivadaEConversaPendenteNaoContamComoAtendente() throws Exception {
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "message_created", "message_type": "outgoing", "sender": {"type": "agent_bot"},
                 "conversation": {"id": 7, "status": "open"}}
                """), "1")).isEmpty();
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "message_created", "message_type": "outgoing", "private": true, "sender": {"type": "user"},
                 "conversation": {"id": 7, "status": "open"}}
                """), "1")).isEmpty();
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "message_created", "message_type": "outgoing", "sender": {"type": "user"},
                 "conversation": {"id": 7, "status": "pending"}}
                """), "1")).isEmpty();
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "message_created", "message_type": "incoming", "conversation": {"id": 7, "status": "pending"}}
                """), "1")).contains(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, "1", "7", null, null));
    }

    @Test
    void etiquetaSoContaQuandoAcabaDeSerAdicionada() throws Exception {
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "conversation_updated", "id": 7, "account_id": 1, "labels": ["vip", "orcamento"],
                 "changed_attributes": [{"label_list": {"previous_value": ["vip"], "current_value": ["vip", "orcamento"]}}]}
                """), "1")).contains(new Funil.Evento(Funil.Evento.Tipo.ETIQUETAS, "1", "7", null, List.of("orcamento")));
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "conversation_updated", "id": 7, "labels": ["orcamento"],
                 "changed_attributes": [{"status": {"previous_value": "open", "current_value": "resolved"}}]}
                """), "1")).isEmpty();
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "conversation_updated", "id": 7,
                 "changed_attributes": [{"cached_label_list": {"previous_value": "vip", "current_value": "vip,orcamento"}}]}
                """), "1")).contains(new Funil.Evento(Funil.Evento.Tipo.ETIQUETAS, "1", "7", null, List.of("orcamento")));
    }

    @Test
    void eventoDeConversaUsaOSegredoDaContaPeloAccountId() throws Exception {
        assertThat(controller.receber("segredo-2", null, null, json.readTree("""
                {"event": "conversation_updated", "id": 7, "account_id": 2, "changed_attributes": []}
                """)).getStatusCode().value()).isEqualTo(200);
    }

    private JsonNode corpo(String accountId, String channel) throws Exception {
        return json.readTree("""
                {
                  "event": "message_created",
                  "message_type": "incoming",
                  "private": false,
                  "content": "oi",
                  "id": 10,
                  "account": {"id": %s},
                  "conversation": {"id": 99, "status": "pending", "channel": "%s"}
                }
                """.formatted(accountId, channel));
    }
}
