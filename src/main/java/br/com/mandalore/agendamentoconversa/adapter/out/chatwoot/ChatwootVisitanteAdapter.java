package br.com.mandalore.agendamentoconversa.adapter.out.chatwoot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.mandalore.agendamentoconversa.application.domain.model.Anexo;
import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootVisitantePort;

@Component
@ConditionalOnProperty(name = "simulador.enabled", havingValue = "true")
public class ChatwootVisitanteAdapter implements ChatwootVisitantePort {

    private static final int MENSAGEM_DO_CONTATO = 0;

    private final RestClient client;
    private final String websiteToken;

    public ChatwootVisitanteAdapter(
            @Value("${chatwoot.base-url:}") String baseUrl,
            @Value("${chatwoot.website-token:}") String websiteToken,
            @Value("${chatwoot.publish-timeout-ms:5000}") int timeoutMs
    ) {
        this.websiteToken = websiteToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
    }

    @Override
    public String criarVisitante() {
        exigirConfiguracao();
        JsonNode config = client.post()
                .uri("/api/v1/widget/config?website_token={token}", websiteToken)
                .body(Map.of())
                .retrieve()
                .body(JsonNode.class);
        String token = config == null ? null : config.path("website_channel_config").path("auth_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Chatwoot não devolveu auth_token do widget");
        }
        return token;
    }

    @Override
    public void enviar(String tokenVisitante, String texto) {
        client.post()
                .uri("/api/v1/widget/messages?website_token={token}", websiteToken)
                .header("X-Auth-Token", tokenVisitante)
                .body(Map.of("message", Map.of("content", texto)))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void enviarArquivo(String tokenVisitante, ConversaVisitante.Arquivo arquivo, String legenda) {
        MultiValueMap<String, Object> corpo = new LinkedMultiValueMap<>();
        if (legenda != null && !legenda.isBlank()) {
            corpo.add("message[content]", legenda);
        }
        corpo.add("message[timestamp]", Instant.now().toString());
        HttpHeaders cabecalho = new HttpHeaders();
        cabecalho.setContentType(MediaType.parseMediaType(arquivo.tipo()));
        cabecalho.setContentDisposition(ContentDisposition.formData()
                .name("message[attachments][]")
                .filename(arquivo.nome().replaceAll("[^A-Za-z0-9._-]", "_"))
                .build());
        corpo.add("message[attachments][]", new HttpEntity<>(new ByteArrayResource(arquivo.conteudo()), cabecalho));
        client.post()
                .uri("/api/v1/widget/messages?website_token={token}", websiteToken)
                .header("X-Auth-Token", tokenVisitante)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(corpo)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public ConversaVisitante ler(String tokenVisitante) {
        JsonNode conversa = conversa(tokenVisitante);
        JsonNode lista = client.get()
                .uri("/api/v1/widget/messages?website_token={token}", websiteToken)
                .header("X-Auth-Token", tokenVisitante)
                .retrieve()
                .body(JsonNode.class);
        List<ConversaVisitante.Mensagem> mensagens = new ArrayList<>();
        if (lista != null) {
            for (JsonNode m : lista.path("payload")) {
                String texto = m.path("content").asText("");
                List<Anexo> anexos = new ArrayList<>();
                for (JsonNode a : m.path("attachments")) {
                    String url = a.path("data_url").asText("");
                    if (!url.isBlank()) {
                        anexos.add(new Anexo(a.path("file_type").asText(""), url));
                    }
                }
                if (texto.isBlank() && anexos.isEmpty()) {
                    continue;
                }
                mensagens.add(new ConversaVisitante.Mensagem(
                        m.path("id").asLong(),
                        texto,
                        m.path("message_type").asInt() == MENSAGEM_DO_CONTATO,
                        m.path("sender").path("name").asText(""),
                        m.path("sender").path("type").asText(""),
                        anexos
                ));
            }
        }
        return new ConversaVisitante(
                conversa == null ? null : conversa.path("id").asText(null),
                conversa == null ? null : conversa.path("status").asText(null),
                mensagens
        );
    }

    private JsonNode conversa(String tokenVisitante) {
        try {
            return client.get()
                    .uri("/api/v1/widget/conversations?website_token={token}", websiteToken)
                    .header("X-Auth-Token", tokenVisitante)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return null;
        }
    }

    private void exigirConfiguracao() {
        if (websiteToken == null || websiteToken.isBlank()) {
            throw new IllegalStateException("CHATWOOT_WEBSITE_TOKEN ausente");
        }
    }
}
