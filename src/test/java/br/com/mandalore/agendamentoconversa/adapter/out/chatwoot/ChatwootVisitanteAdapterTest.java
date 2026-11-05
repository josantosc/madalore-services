package br.com.mandalore.agendamentoconversa.adapter.out.chatwoot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;

class ChatwootVisitanteAdapterTest {

    private HttpServer servidor;
    private final AtomicReference<String> tipo = new AtomicReference<>();
    private final AtomicReference<String> corpo = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private ChatwootVisitanteAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/v1/widget/messages", troca -> {
            if ("POST".equals(troca.getRequestMethod())) {
                tipo.set(troca.getRequestHeaders().getFirst("Content-Type"));
                token.set(troca.getRequestHeaders().getFirst("X-Auth-Token"));
                corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                responder(troca, "{}");
            } else {
                responder(troca, """
                        {"payload":[
                          {"id":1,"content":null,"message_type":0,"sender":{"name":"v","type":"contact"},
                           "attachments":[{"file_type":"image","data_url":"http://cw/rails/pedido.jpg"}]},
                          {"id":2,"content":"","message_type":1,"sender":{"name":"bot","type":"agent_bot"}}
                        ]}""");
            }
        });
        servidor.createContext("/api/v1/widget/conversations", troca -> responder(troca, "{\"id\":27,\"status\":\"pending\"}"));
        servidor.start();
        adapter = new ChatwootVisitanteAdapter("http://127.0.0.1:" + servidor.getAddress().getPort(), "wt", 5000);
    }

    @AfterEach
    void tearDown() {
        servidor.stop(0);
    }

    @Test
    void enviaArquivoNoFormatoDoWidget() {
        adapter.enviarArquivo("tok", new ConversaVisitante.Arquivo("pedido.jpg", "image/jpeg", new byte[] {7, 8}), "meu pedido");

        assertThat(token.get()).isEqualTo("tok");
        assertThat(tipo.get()).startsWith("multipart/form-data");
        assertThat(corpo.get())
                .contains("name=\"message[content]\"", "meu pedido")
                .contains("name=\"message[attachments][]\"; filename=\"pedido.jpg\"", "Content-Type: image/jpeg");
    }

    @Test
    void leMensagemSoComAnexoEDescartaVazia() {
        ConversaVisitante conversa = adapter.ler("tok");

        assertThat(conversa.conversationId()).isEqualTo("27");
        assertThat(conversa.mensagens()).singleElement().satisfies(m -> {
            assertThat(m.doPaciente()).isTrue();
            assertThat(m.anexos()).singleElement().satisfies(a -> {
                assertThat(a.tipo()).isEqualTo("image");
                assertThat(a.documento()).isTrue();
            });
        });
    }

    private static void responder(HttpExchange troca, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(200, bytes.length);
        troca.getResponseBody().write(bytes);
        troca.close();
    }
}
