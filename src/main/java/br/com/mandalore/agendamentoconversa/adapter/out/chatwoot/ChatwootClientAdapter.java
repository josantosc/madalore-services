package br.com.mandalore.agendamentoconversa.adapter.out.chatwoot;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;

@Component
public class ChatwootClientAdapter implements ChatwootPort {

    private static final int LIMITE_ANEXO = 10 * 1_048_576;

    private final RestClient client;
    private final RestClient download;
    private final String base;
    private final String tokenGlobal;
    private final ClientesProperties clientes;
    private final Map<Setor, String> equipes;

    public ChatwootClientAdapter(
            @Value("${chatwoot.base-url:}") String baseUrl,
            @Value("${chatwoot.api-token:}") String token,
            @Value("${chatwoot.publish-timeout-ms:5000}") int timeoutMs,
            @Value("${chatwoot.equipes.recepcao:}") String recepcao,
            @Value("${chatwoot.equipes.laboratorio:}") String laboratorio,
            @Value("${chatwoot.equipes.faturamento:}") String faturamento,
            ClientesProperties clientes
    ) {
        this.tokenGlobal = token;
        this.clientes = clientes;
        this.equipes = new EnumMap<>(Setor.class);
        equipes.put(Setor.RECEPCAO, recepcao.trim());
        equipes.put(Setor.LABORATORIO, laboratorio.trim());
        equipes.put(Setor.FATURAMENTO, faturamento.trim());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/$", "");
        this.client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
        SimpleClientHttpRequestFactory fabricaDownload = new SimpleClientHttpRequestFactory();
        fabricaDownload.setConnectTimeout(Duration.ofSeconds(3));
        fabricaDownload.setReadTimeout(Duration.ofSeconds(20));
        this.download = RestClient.builder().requestFactory(fabricaDownload).build();
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

    @Override
    public boolean atribuirEquipe(String accountId, String conversationId, Setor setor) {
        String equipe = equipes.getOrDefault(setor, "");
        if (equipe.isBlank()) {
            return false;
        }
        client.post()
                .uri("/api/v1/accounts/{accountId}/conversations/{conversationId}/assignments", accountId, conversationId)
                .header("api_access_token", token(accountId))
                .body(Map.of("team_id", Long.parseLong(equipe)))
                .retrieve()
                .toBodilessEntity();
        return true;
    }

    @Override
    public void notaPrivada(String accountId, String conversationId, String texto) {
        client.post()
                .uri("/api/v1/accounts/{accountId}/conversations/{conversationId}/messages", accountId, conversationId)
                .header("api_access_token", token(accountId))
                .body(Map.of("content", texto, "message_type", "outgoing", "private", true))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public Orcamento.Arquivo baixar(String url) {
        return download.get()
                .uri(noChatwoot(url))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("Anexo indisponível: HTTP " + response.getStatusCode().value());
                    }
                    if (response.getHeaders().getContentLength() > LIMITE_ANEXO) {
                        throw new IllegalStateException("Anexo acima de " + LIMITE_ANEXO / 1_048_576 + " MB");
                    }
                    byte[] conteudo = response.getBody().readNBytes(LIMITE_ANEXO + 1);
                    if (conteudo.length > LIMITE_ANEXO) {
                        throw new IllegalStateException("Anexo acima de " + LIMITE_ANEXO / 1_048_576 + " MB");
                    }
                    MediaType tipo = response.getHeaders().getContentType();
                    return new Orcamento.Arquivo(conteudo, tipo == null ? mimePorExtensao(url) : tipo.getType() + "/" + tipo.getSubtype());
                });
    }

    /** O data_url usa o domínio público do Chatwoot; baixa pelo CHATWOOT_BASE_URL e nunca de outro host. */
    URI noChatwoot(String url) {
        URI anexo = URI.create(url);
        if (base.isBlank()) {
            return anexo;
        }
        return URI.create(base + anexo.getRawPath() + (anexo.getRawQuery() == null ? "" : "?" + anexo.getRawQuery()));
    }

    private static String mimePorExtensao(String url) {
        String caminho = url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        if (caminho.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (caminho.endsWith(".png")) {
            return "image/png";
        }
        if (caminho.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    String token(String accountId) {
        return clientes.cliente(accountId)
                .map(ClientesProperties.Cliente::chatwootToken)
                .filter(token -> !token.isBlank())
                .orElse(tokenGlobal);
    }
}
