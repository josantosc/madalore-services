package br.com.mandalore.agendamentoconversa.adapter.out.dialogflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.observability.Mascara;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;
import br.com.mandalore.agendamentoconversa.application.domain.service.Textos;

@Component
public class DialogflowSessionsAdapter implements DialogflowPort {

    private static final Logger LOG = LoggerFactory.getLogger(DialogflowSessionsAdapter.class);
    private static final int LIMITE_TEXTO = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String projectId;
    private final DialogflowCredenciais credenciais;
    private final String idioma;
    private final String fuso;
    private final String ambiente;
    private final String regiao;
    private final RestClient client;
    private final FlowTelemetry telemetry;
    private final boolean curadoriaTexto;

    public DialogflowSessionsAdapter(
            @Value("${dialogflow.project-id:}") String projectId,
            DialogflowCredenciais credenciais,
            @Value("${dialogflow.language-code:pt-BR}") String idioma,
            @Value("${dialogflow.time-zone:America/Sao_Paulo}") String fuso,
            @Value("${dialogflow.environment:}") String ambiente,
            @Value("${dialogflow.location:}") String regiao,
            @Value("${dialogflow.detect-timeout-ms:8000}") int timeoutMs,
            @Value("${observability.curadoria.texto:true}") boolean curadoriaTexto,
            FlowTelemetry telemetry
    ) {
        this.curadoriaTexto = curadoriaTexto;
        this.projectId = projectId;
        this.credenciais = credenciais;
        this.idioma = idioma;
        this.fuso = fuso;
        this.ambiente = ambiente == null ? "" : ambiente.trim();
        this.regiao = regiao == null ? "" : regiao.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .baseUrl(DialogflowCredenciais.host(this.regiao))
                .requestFactory(factory)
                .build();
        this.telemetry = telemetry;
    }

    @Override
    public RespostaDialogflow detectar(String sessionId, String texto) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("DIALOGFLOW_PROJECT_ID ausente");
        }
        String sessao = sessaoValida(sessionId);
        String consulta = limitarTexto(texto);
        String conversationId = Textos.conversationId(sessao);
        long inicio = System.nanoTime();
        try {
            JsonNode resposta = client.post()
                    .uri(caminho(), variaveis(sessao))
                    .header("Authorization", "Bearer " + credenciais.token())
                    .body(Map.of(
                            "queryParams", Map.of("timeZone", fuso),
                            "queryInput", Map.of(
                                    "text", Map.of(
                                            "text", consulta,
                                            "languageCode", idioma
                                    )
                            )
                    ))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode query = resposta == null ? null : resposta.path("queryResult");
            String intent = query == null ? "" : query.path("intent").path("displayName").asText("");
            String action = query == null ? "" : query.path("action").asText("");
            registrarWebhook(resposta, conversationId, intent, action);
            String fulfillment = textoResposta(query);
            FlowEvent.Builder evento = FlowEvent.builder("DIALOGFLOW", "detect_intent", "SUCCESS")
                    .correlation(conversationId, conversationId)
                    .intent(intent, action)
                    .httpStatus(200)
                    .durationMs(elapsed(inicio));
            if (query != null) {
                evento.detail("fallback", query.path("intent").path("isFallback").asBoolean(false));
                if (query.has("intentDetectionConfidence")) {
                    evento.detail("confianca", query.path("intentDetectionConfidence").asDouble());
                }
            }
            if (curadoriaTexto) {
                evento.detail("texto", Mascara.texto(consulta));
            }
            telemetry.record(evento.build());
            return new RespostaDialogflow(
                    intent,
                    fulfillment,
                    action,
                    parametros(query),
                    contextos(query),
                    erroWebhook(resposta)
            );
        } catch (RestClientResponseException ex) {
            telemetry.record(FlowEvent.builder("DIALOGFLOW", "detect_intent", "ERROR")
                    .correlation(conversationId, conversationId)
                    .httpStatus(ex.getStatusCode().value())
                    .error("DIALOGFLOW_HTTP_" + ex.getStatusCode().value(), ex)
                    .durationMs(elapsed(inicio))
                    .build());
            throw ex;
        } catch (RuntimeException ex) {
            telemetry.record(FlowEvent.builder("DIALOGFLOW", "detect_intent", "ERROR")
                    .correlation(conversationId, conversationId)
                    .error("DIALOGFLOW_REQUEST_FAILED", ex)
                    .durationMs(elapsed(inicio))
                    .build());
            throw ex;
        }
    }

    private String caminho() {
        if (!regiao.isBlank() && !ambiente.isBlank()) {
            return "/v2/projects/{projectId}/locations/{location}/agent/environments/{environment}/users/-/sessions/{sessionId}:detectIntent";
        }
        if (!ambiente.isBlank()) {
            return "/v2/projects/{projectId}/agent/environments/{environment}/users/-/sessions/{sessionId}:detectIntent";
        }
        if (!regiao.isBlank()) {
            return "/v2/projects/{projectId}/locations/{location}/agent/sessions/{sessionId}:detectIntent";
        }
        return "/v2/projects/{projectId}/agent/sessions/{sessionId}:detectIntent";
    }

    private Map<String, String> variaveis(String sessionId) {
        Map<String, String> valores = new LinkedHashMap<>();
        valores.put("projectId", projectId);
        if (!regiao.isBlank()) {
            valores.put("location", regiao);
        }
        if (!ambiente.isBlank()) {
            valores.put("environment", ambiente);
        }
        valores.put("sessionId", sessionId);
        return valores;
    }

    private String sessaoValida(String sessionId) {
        String id = sessionId == null ? "" : sessionId;
        int barra = id.lastIndexOf('/');
        if (barra >= 0) {
            id = id.substring(barra + 1);
        }
        if (id.length() > 36 || !id.matches("[A-Za-z0-9_-]+")) {
            id = Textos.sessionId("sessao", id);
        }
        return id;
    }

    private String limitarTexto(String texto) {
        String consulta = texto == null ? "" : texto.trim();
        if (consulta.length() <= LIMITE_TEXTO) {
            return consulta;
        }
        LOG.info("detectIntent texto acima de 256 caracteres");
        return consulta.substring(0, LIMITE_TEXTO);
    }

    private void registrarWebhook(
            JsonNode resposta,
            String conversationId,
            String intent,
            String action
    ) {
        if (resposta == null) {
            return;
        }
        JsonNode status = resposta.path("webhookStatus");
        if (status.isMissingNode() || status.isNull()) {
            return;
        }
        int codigo = status.path("code").asInt(0);
        if (codigo != 0) {
            String message = status.path("message").asText("");
            LOG.warn("dialogflow webhook_status={} webhook_message={}", codigo, message);
            telemetry.record(FlowEvent.builder("DIALOGFLOW", "fulfillment_webhook", "ERROR")
                    .correlation(conversationId, conversationId)
                    .intent(intent, action)
                    .error(String.valueOf(codigo), "DialogflowWebhookException", message)
                    .build());
        }
    }

    private Map<String, Object> parametros(JsonNode query) {
        if (query == null || !query.path("parameters").isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(query.path("parameters"), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private List<RespostaDialogflow.ContextoAtivo> contextos(JsonNode query) {
        List<RespostaDialogflow.ContextoAtivo> ativos = new ArrayList<>();
        if (query == null || !query.path("outputContexts").isArray()) {
            return ativos;
        }
        for (JsonNode contexto : query.path("outputContexts")) {
            String nome = contexto.path("name").asText("");
            int barra = nome.lastIndexOf('/');
            ativos.add(new RespostaDialogflow.ContextoAtivo(
                    barra < 0 ? nome : nome.substring(barra + 1),
                    contexto.path("lifespanCount").asInt(0)
            ));
        }
        return ativos;
    }

    private String erroWebhook(JsonNode resposta) {
        JsonNode status = resposta == null ? null : resposta.path("webhookStatus");
        if (status == null || status.isMissingNode() || status.path("code").asInt(0) == 0) {
            return null;
        }
        return status.path("message").asText("");
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String textoResposta(JsonNode query) {
        if (query == null) {
            return "";
        }
        List<String> partes = new ArrayList<>();
        JsonNode messages = query.path("fulfillmentMessages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode text = message.path("text").path("text");
                if (text.isArray() && !text.isEmpty() && !text.get(0).asText("").isBlank()) {
                    partes.add(text.get(0).asText("").trim());
                }
            }
        }
        if (!partes.isEmpty()) {
            return String.join("\n\n", partes);
        }
        return query.path("fulfillmentText").asText("");
    }

}
