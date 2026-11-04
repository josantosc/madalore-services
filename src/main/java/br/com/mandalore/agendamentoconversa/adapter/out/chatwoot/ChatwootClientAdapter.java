package br.com.mandalore.agendamentoconversa.adapter.out.chatwoot;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;

@Component
public class ChatwootClientAdapter implements ChatwootPort {

    private final RestClient client;
    private final String tokenGlobal;
    private final ClientesProperties clientes;

    public ChatwootClientAdapter(
            @Value("${chatwoot.base-url:}") String baseUrl,
            @Value("${chatwoot.api-token:}") String token,
            @Value("${chatwoot.publish-timeout-ms:5000}") int timeoutMs,
            ClientesProperties clientes
    ) {
        this.tokenGlobal = token;
        this.clientes = clientes;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
    }

    @Override
    public void publicar(String accountId, String conversationId, String texto) {
        client.post()
                .uri("/api/v1/accounts/{accountId}/conversations/{conversationId}/messages", accountId, conversationId)
                .header("api_access_token", token(accountId))
                .body(Map.of("content", texto, "message_type", "outgoing"))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void abrirAtendente(String accountId, String conversationId) {
        client.post()
                .uri("/api/v1/accounts/{accountId}/conversations/{conversationId}/toggle_status", accountId, conversationId)
                .header("api_access_token", token(accountId))
                .body(Map.of("status", "open"))
                .retrieve()
                .toBodilessEntity();
    }

    String token(String accountId) {
        return clientes.cliente(accountId)
                .map(ClientesProperties.Cliente::chatwootToken)
                .filter(token -> !token.isBlank())
                .orElse(tokenGlobal);
    }
}
