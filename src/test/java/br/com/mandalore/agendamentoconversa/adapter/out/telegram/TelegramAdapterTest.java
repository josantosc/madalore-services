package br.com.mandalore.agendamentoconversa.adapter.out.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class TelegramAdapterTest {

    private static final String TOKEN = "123:segredo";

    private HttpServer servidor;
    private final AtomicReference<String> caminho = new AtomicReference<>();
    private final AtomicReference<String> corpo = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            caminho.set(troca.getRequestURI().getPath());
            corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resposta = (status.get() == 200 ? "{\"ok\":true}" : "{\"ok\":false,\"description\":\"chat not found\"}")
                    .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status.get(), resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        servidor.stop(0);
    }

    @Test
    void enviaTextoParaOGrupo() throws Exception {
        new TelegramAdapter(base, TOKEN, "-100123").enviar("Curadoria do bot\nMensagens: 10");

        assertThat(caminho.get()).isEqualTo("/bot" + TOKEN + "/sendMessage");
        JsonNode json = new ObjectMapper().readTree(corpo.get());
        assertThat(json.path("chat_id").asText()).isEqualTo("-100123");
        assertThat(json.path("text").asText()).isEqualTo("Curadoria do bot\nMensagens: 10");
    }

    @Test
    void cortaTextoAcimaDoLimite() throws Exception {
        new TelegramAdapter(base, TOKEN, "-100123").enviar("x".repeat(5000));

        assertThat(new ObjectMapper().readTree(corpo.get()).path("text").asText()).hasSize(TelegramAdapter.LIMITE);
    }

    @Test
    void erroNaoExpoeOToken() {
        status.set(400);

        assertThatThrownBy(() -> new TelegramAdapter(base, TOKEN, "-1").enviar("oi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("chat not found")
                .hasMessageNotContaining("segredo");
    }

    @Test
    void falhaDeConexaoNaoExpoeOToken() {
        servidor.stop(0);

        assertThatThrownBy(() -> new TelegramAdapter(base, TOKEN, "-1").enviar("oi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("segredo");
    }

    @Test
    void semConfiguracaoNaoTentaEnviar() {
        assertThatThrownBy(() -> new TelegramAdapter(base, "", "").enviar("oi"))
                .hasMessageContaining("TELEGRAM_BOT_TOKEN");
        assertThat(caminho.get()).isNull();
    }
}
