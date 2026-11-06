package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties.Cliente;
import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
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
