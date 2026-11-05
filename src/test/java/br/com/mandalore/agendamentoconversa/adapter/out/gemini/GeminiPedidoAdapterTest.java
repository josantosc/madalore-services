package br.com.mandalore.agendamentoconversa.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.adapter.out.google.GoogleCredenciais;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;

class GeminiPedidoAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiPedidoAdapter adapter = new GeminiPedidoAdapter(
            new GoogleCredenciais(""), mapper, "projeto", "global", "gemini-2.5-flash", 1000);

    @Test
    void interpretaJsonEstruturado() throws Exception {
        String texto = mapper.writeValueAsString(Map.of(
                "pedido_medico", true,
                "exames", List.of(
                        Map.of("lido", "HMG", "item", "Hemograma completo"),
                        Map.of("lido", " ", "item", ""),
                        Map.of("lido", "Vit D", "item", ""))));
        JsonNode resposta = mapper.readTree(mapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", texto))))))));

        Orcamento.PedidoLido pedido = adapter.interpretar(resposta);

        assertThat(pedido.pedidoMedico()).isTrue();
        assertThat(pedido.exames()).containsExactly(
                new Orcamento.ExameLido("HMG", "Hemograma completo"),
                new Orcamento.ExameLido("Vit D", ""));
    }

    @Test
    void respostaBloqueadaViraErro() throws Exception {
        JsonNode resposta = mapper.readTree("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");

        assertThatThrownBy(() -> adapter.interpretar(resposta)).hasMessageContaining("SAFETY");
    }

    @Test
    void corpoLevaImagemCatalogoEEsquema() {
        Map<String, Object> corpo = GeminiPedidoAdapter.corpo(
                new Orcamento.Arquivo(new byte[] {1, 2, 3}, "image/png"), List.of("Glicose", "TSH"));

        JsonNode json = mapper.valueToTree(corpo);
        JsonNode partes = json.path("contents").path(0).path("parts");
        assertThat(partes.path(0).path("inlineData").path("mimeType").asText()).isEqualTo("image/png");
        assertThat(partes.path(0).path("inlineData").path("data").asText()).isEqualTo("AQID");
        assertThat(partes.path(1).path("text").asText()).contains("- Glicose\n- TSH\n").contains("Não transcreva nome");
        assertThat(json.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
    }

    @Test
    void hostRegional() {
        assertThat(GeminiPedidoAdapter.host("global")).isEqualTo("https://aiplatform.googleapis.com");
        assertThat(GeminiPedidoAdapter.host("southamerica-east1"))
                .isEqualTo("https://southamerica-east1-aiplatform.googleapis.com");
    }
}
