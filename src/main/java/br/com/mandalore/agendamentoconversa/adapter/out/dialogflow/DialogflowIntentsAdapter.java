package br.com.mandalore.agendamentoconversa.adapter.out.dialogflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.TreinamentoPort;

/**
 * Intents do agente (draft) via API v2. Editar exige o papel "Dialogflow API Admin" na conta de serviço.
 * Com DIALOGFLOW_ENVIRONMENT, a frase só vale no ambiente depois de publicar uma versão nova.
 */
@Component
@ConditionalOnProperty(name = "curadoria.enabled", havingValue = "true")
public class DialogflowIntentsAdapter implements TreinamentoPort {

    private static final Logger LOG = LoggerFactory.getLogger(DialogflowIntentsAdapter.class);

    private final String projectId;
    private final String regiao;
    private final String idioma;
    private final DialogflowCredenciais credenciais;
    private final RestClient client;

    public DialogflowIntentsAdapter(
            @Value("${dialogflow.project-id:}") String projectId,
            @Value("${dialogflow.location:}") String regiao,
            @Value("${dialogflow.language-code:pt-BR}") String idioma,
            DialogflowCredenciais credenciais
    ) {
        this.projectId = projectId;
        this.regiao = regiao == null ? "" : regiao.trim();
        this.idioma = idioma;
        this.credenciais = credenciais;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder()
                .baseUrl(DialogflowCredenciais.host(this.regiao))
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<String> intents() {
        List<String> nomes = new ArrayList<>();
        listar().forEach(intent -> nomes.add(intent.path("displayName").asText("")));
        nomes.removeIf(String::isBlank);
        nomes.sort(String.CASE_INSENSITIVE_ORDER);
        return nomes;
    }

    @Override
    public boolean ensinar(String intent, String frase) {
        String nome = listar().stream()
                .filter(i -> intent.equals(i.path("displayName").asText("")))
                .map(i -> i.path("name").asText(""))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("intent não encontrada: " + intent));
        String token = credenciais.token();
        JsonNode atual = client.get()
                .uri(b -> b.path("/v2/" + nome)
                        .queryParam("languageCode", idioma)
                        .queryParam("intentView", "INTENT_VIEW_FULL")
                        .build())
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JsonNode.class);
        if (!(atual instanceof ObjectNode corpo) || !comFrase(corpo, frase)) {
            return false;
        }
        client.patch()
                .uri(b -> b.path("/v2/" + nome)
                        .queryParam("languageCode", idioma)
                        .queryParam("updateMask", "trainingPhrases")
                        .build())
                .header("Authorization", "Bearer " + token)
                .body(corpo)
                .retrieve()
                .toBodilessEntity();
        treinar(token);
        return true;
    }

    /** Acrescenta a frase de exemplo; false se já houver uma igual (sem diferenciar maiúsculas). */
    static boolean comFrase(ObjectNode intent, String frase) {
        String alvo = frase.trim().toLowerCase(Locale.ROOT);
        ArrayNode frases = intent.has("trainingPhrases") && intent.get("trainingPhrases").isArray()
                ? (ArrayNode) intent.get("trainingPhrases")
                : intent.putArray("trainingPhrases");
        for (JsonNode existente : frases) {
            StringBuilder texto = new StringBuilder();
            existente.path("parts").forEach(parte -> texto.append(parte.path("text").asText("")));
            if (texto.toString().trim().toLowerCase(Locale.ROOT).equals(alvo)) {
                return false;
            }
        }
        ObjectNode nova = frases.addObject();
        nova.put("type", "EXAMPLE");
        nova.putArray("parts").addObject().put("text", frase.trim());
        return true;
    }

    private List<JsonNode> listar() {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("DIALOGFLOW_PROJECT_ID ausente");
        }
        String token = credenciais.token();
        List<JsonNode> todas = new ArrayList<>();
        String pagina = "";
        do {
            String atual = pagina;
            JsonNode resposta = client.get()
                    .uri(b -> {
                        b.path("/v2/" + agente() + "/intents")
                                .queryParam("languageCode", idioma)
                                .queryParam("pageSize", 1000);
                        if (!atual.isBlank()) {
                            b.queryParam("pageToken", atual);
                        }
                        return b.build();
                    })
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(JsonNode.class);
            if (resposta == null) {
                break;
            }
            resposta.path("intents").forEach(todas::add);
            pagina = resposta.path("nextPageToken").asText("");
        } while (!pagina.isBlank());
        return todas;
    }

    private void treinar(String token) {
        try {
            client.post()
                    .uri("/v2/" + agente() + ":train")
                    .header("Authorization", "Bearer " + token)
                    .body(Map.of())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            LOG.warn("curadoria treino do agente nao iniciado error_type={}", ex.getClass().getSimpleName());
        }
    }

    private String agente() {
        return regiao.isBlank()
                ? "projects/" + projectId + "/agent"
                : "projects/" + projectId + "/locations/" + regiao + "/agent";
    }
}
