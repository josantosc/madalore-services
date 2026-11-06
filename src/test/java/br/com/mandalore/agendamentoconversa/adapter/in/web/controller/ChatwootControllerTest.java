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
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.OrcarPedidoUseCase;
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
    void notaPrivadaComOrcamentoViraComandoParaCalcular() throws Exception {
        OrcarPedidoUseCase orcamento = mock(OrcarPedidoUseCase.class);
        controller = new ChatwootController(useCase, "segredo-global", new ClientesProperties(Map.of()),
                () -> null, () -> orcamento);

        controller.receber("segredo-global", null, null, json.readTree("""
                {"event": "message_created", "message_type": "outgoing", "private": true, "id": 31,
                 "content": "/Orçamento hemograma,\\nTSH", "account": {"id": 1},
                 "sender": {"id": 3, "name": "Ana", "type": "user"}, "conversation": {"id": 7, "status": "open"}}
                """));

        verify(orcamento).orcar(new Orcamento.Comando("1", "7", "31", "hemograma,\nTSH", "Ana"));
    }

    @Test
    void soNotaPrivadaDeAtendenteQueComecaComOComandoConta() throws Exception {
        String base = """
                {"event": "message_created", "message_type": "%s", "private": %s, "id": 31, "content": "%s",
                 "sender": {"type": "%s"}, "conversation": {"id": 7}}
                """;
        assertThat(ChatwootController.comandoOrcamento(json.readTree(base.formatted("outgoing", false, "/orcamento tsh", "user")), "1")).isEmpty();
        assertThat(ChatwootController.comandoOrcamento(json.readTree(base.formatted("outgoing", true, "/orcamento tsh", "agent_bot")), "1")).isEmpty();
        assertThat(ChatwootController.comandoOrcamento(json.readTree(base.formatted("incoming", true, "/orcamento tsh", "contact")), "1")).isEmpty();
        assertThat(ChatwootController.comandoOrcamento(json.readTree(base.formatted("outgoing", true, "veja /orcamento tsh", "user")), "1")).isEmpty();
        assertThat(ChatwootController.comandoOrcamento(json.readTree(base.formatted("outgoing", true, "/orcamentos tsh", "user")), "1")).isEmpty();
        assertThat(ChatwootController.comandoOrcamento(json.readTree(base.formatted("outgoing", true, "/orcamento", "user")), "1"))
                .hasValueSatisfying(c -> assertThat(c.exames()).isEmpty());
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
                """), "1")).contains(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "7", null, List.of("orcamento")));
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "conversation_updated", "id": 7, "labels": ["orcamento"],
                 "changed_attributes": [{"status": {"previous_value": "open", "current_value": "resolved"}}]}
                """), "1")).isEmpty();
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "conversation_updated", "id": 7,
                 "changed_attributes": [{"cached_label_list": {"previous_value": "vip", "current_value": "vip,orcamento"}}]}
                """), "1")).contains(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "7", null, List.of("orcamento")));
    }

    @Test
    void nomeDoContatoEAtendenteAtribuidoVemDoMetaDaConversa() throws Exception {
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "conversation_updated", "id": 7, "changed_attributes": [{"assignee_id": {}}],
                 "meta": {"sender": {"name": "Maria"}, "assignee": {"name": "Bruna"}}}
                """), "1")).contains(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "7", null, List.of(), "Maria", "Bruna"));
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "message_created", "message_type": "incoming", "sender": {"name": "Maria", "type": "contact"},
                 "conversation": {"id": 7, "status": "open"}}
                """), "1")).contains(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, "1", "7", null, null, "Maria", null));
        assertThat(ChatwootController.eventoFunil(json.readTree("""
                {"event": "message_created", "message_type": "outgoing", "sender": {"name": "Ana", "type": "user"},
                 "conversation": {"id": 7, "status": "open", "meta": {"sender": {"name": "Maria"}, "assignee": {"name": "Bruna"}}}}
                """), "1")).contains(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null, "Maria", "Bruna"));
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
