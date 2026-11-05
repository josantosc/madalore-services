package br.com.mandalore.agendamentoconversa.adapter.out.telegram;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.NotificacaoPort;

/** Bot do Telegram (sendMessage) para o grupo da equipe. */
@Component
@ConditionalOnProperty(name = "relatorios.enabled", havingValue = "true")
public class TelegramAdapter implements NotificacaoPort {

    /** Limite do Telegram por mensagem. */
    static final int LIMITE = 4096;

    private final RestClient client;
    private final String token;
    private final String chatId;

    public TelegramAdapter(
            @Value("${relatorios.telegram.base-url:https://api.telegram.org}") String baseUrl,
            @Value("${relatorios.telegram.token:}") String token,
            @Value("${relatorios.telegram.chat-id:}") String chatId
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(baseUrl.trim().replaceAll("/$", "")).requestFactory(factory).build();
        this.token = token == null ? "" : token.trim();
        this.chatId = chatId == null ? "" : chatId.trim();
    }

    @Override
    public void enviar(String texto) {
        if (token.isEmpty() || chatId.isEmpty()) {
            throw new IllegalStateException("Defina TELEGRAM_BOT_TOKEN e TELEGRAM_CHAT_ID");
        }
        String corpo = texto.length() > LIMITE ? texto.substring(0, LIMITE - 1) + "…" : texto;
        try {
            client.post()
                    .uri("/bot{token}/sendMessage", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", corpo, "disable_web_page_preview", true))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            // A URL da requisição contém o token: a mensagem de erro não pode carregá-la.
            throw new IllegalStateException("Telegram recusou a mensagem: HTTP " + ex.getStatusCode().value()
                    + " " + ex.getResponseBodyAsString());
        } catch (RestClientException ex) {
            throw new IllegalStateException("Falha ao falar com o Telegram: " + ex.getClass().getSimpleName());
        }
    }
}
