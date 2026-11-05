package br.com.mandalore.agendamentoconversa.adapter.out.gemini;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.adapter.out.google.GoogleCredenciais;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.LeitorPedidoPort;

/**
 * Gemini no Vertex AI. A conta de serviço precisa do papel "Vertex AI User"
 * e a API aiplatform.googleapis.com ativa no projeto. O Vertex não usa os dados para treino.
 */
@Component
@ConditionalOnProperty(name = "orcamento.enabled", havingValue = "true")
public class GeminiPedidoAdapter implements LeitorPedidoPort {

    static final int LIMITE_CATALOGO = 800;

    private static final String INSTRUCAO = """
            Você recebe a imagem de um pedido médico (requisição de exames) enviada por um paciente.
            Liste somente os exames solicitados, um por item, como estão escritos no pedido.
            Para cada exame, se ele corresponder a um item do catálogo abaixo (inclusive por sigla ou abreviação,
            como HMG = hemograma, TGO = AST), preencha "item" com o nome exato do catálogo; senão deixe "item" vazio.
            Não transcreva nome do paciente, documentos, médico, CRM, CID, endereço nem qualquer outro dado.
            Se a imagem não for um pedido médico de exames, responda pedido_medico=false e exames vazio.
            Catálogo:
            """;

    private static final Map<String, Object> ESQUEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "pedido_medico", Map.of("type", "BOOLEAN"),
                    "exames", Map.of(
                            "type", "ARRAY",
                            "items", Map.of(
                                    "type", "OBJECT",
                                    "properties", Map.of(
                                            "lido", Map.of("type", "STRING"),
                                            "item", Map.of("type", "STRING")),
                                    "required", List.of("lido", "item")))),
            "required", List.of("pedido_medico", "exames"));

    private final GoogleCredenciais credenciais;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String caminho;

    public GeminiPedidoAdapter(
            GoogleCredenciais credenciais,
            ObjectMapper mapper,
            @Value("${orcamento.gemini.project-id:}") String projeto,
            @Value("${orcamento.gemini.location:global}") String regiao,
            @Value("${orcamento.gemini.model:gemini-3.8-flash}") String modelo,
            @Value("${orcamento.gemini.timeout-ms:60000}") int timeoutMs
    ) {
        this.credenciais = credenciais;
        this.mapper = mapper;
        String local = regiao == null || regiao.isBlank() ? "global" : regiao.trim();
        this.caminho = "/v1/projects/" + projeto.trim() + "/locations/" + local
                + "/publishers/google/models/" + modelo.trim() + ":generateContent";
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder().baseUrl(host(local)).requestFactory(factory).build();
    }

    static String host(String regiao) {
        return "global".equals(regiao)
                ? "https://aiplatform.googleapis.com"
                : "https://" + regiao + "-aiplatform.googleapis.com";
    }

    @Override
    public Orcamento.PedidoLido ler(Orcamento.Arquivo arquivo, List<String> catalogo) {
        JsonNode resposta = client.post()
                .uri(caminho)
                .header("Authorization", "Bearer " + credenciais.token(GoogleCredenciais.CLOUD_PLATFORM))
                .body(corpo(arquivo, catalogo))
                .retrieve()
                .body(JsonNode.class);
        return interpretar(resposta);
    }

    static Map<String, Object> corpo(Orcamento.Arquivo arquivo, List<String> catalogo) {
        StringBuilder texto = new StringBuilder(INSTRUCAO);
        catalogo.stream().limit(LIMITE_CATALOGO).forEach(nome -> texto.append("- ").append(nome).append('\n'));
        return Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(
                                Map.of("inlineData", Map.of(
                                        "mimeType", arquivo.mimeType(),
                                        "data", Base64.getEncoder().encodeToString(arquivo.conteudo()))),
                                Map.of("text", texto.toString())))),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "responseMimeType", "application/json",
                        "responseSchema", ESQUEMA));
    }

    Orcamento.PedidoLido interpretar(JsonNode resposta) {
        JsonNode candidato = resposta == null ? null : resposta.path("candidates").path(0);
        if (candidato == null || candidato.isMissingNode()) {
            throw new IllegalStateException("Gemini sem resposta: " + (resposta == null ? "" : resposta.path("promptFeedback")));
        }
        StringBuilder json = new StringBuilder();
        candidato.path("content").path("parts").forEach(parte -> json.append(parte.path("text").asText("")));
        if (json.isEmpty()) {
            throw new IllegalStateException("Gemini sem texto: finishReason=" + candidato.path("finishReason").asText());
        }
        try {
            JsonNode lido = mapper.readTree(json.toString());
            List<Orcamento.ExameLido> exames = new ArrayList<>();
            lido.path("exames").forEach(exame -> {
                String nome = exame.path("lido").asText("").trim();
                if (!nome.isEmpty()) {
                    exames.add(new Orcamento.ExameLido(nome, exame.path("item").asText("").trim()));
                }
            });
            return new Orcamento.PedidoLido(lido.path("pedido_medico").asBoolean(false), exames);
        } catch (Exception ex) {
            throw new IllegalStateException("Resposta do Gemini fora do formato", ex);
        }
    }
}
