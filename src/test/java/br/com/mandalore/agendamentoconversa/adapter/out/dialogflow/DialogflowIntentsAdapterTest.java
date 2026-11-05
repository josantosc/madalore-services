package br.com.mandalore.agendamentoconversa.adapter.out.dialogflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class DialogflowIntentsAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acrescentaFraseDeExemploMantendoAsExistentes() throws Exception {
        ObjectNode intent = (ObjectNode) mapper.readTree("""
                {"name":"projects/p/agent/intents/1","displayName":"acao.agenda.agendar",
                 "trainingPhrases":[{"type":"EXAMPLE","parts":[{"text":"marcar "},{"text":"consulta","entityType":"@sys.any"}]}]}
                """);

        assertThat(DialogflowIntentsAdapter.comFrase(intent, " quero agendar ")).isTrue();

        assertThat(intent.get("trainingPhrases")).hasSize(2);
        assertThat(intent.at("/trainingPhrases/1/type").asText()).isEqualTo("EXAMPLE");
        assertThat(intent.at("/trainingPhrases/1/parts/0/text").asText()).isEqualTo("quero agendar");
    }

    @Test
    void ignoraFraseRepetidaSemDiferenciarMaiusculas() throws Exception {
        ObjectNode intent = (ObjectNode) mapper.readTree("""
                {"trainingPhrases":[{"type":"EXAMPLE","parts":[{"text":"Marcar "},{"text":"Consulta"}]}]}
                """);

        assertThat(DialogflowIntentsAdapter.comFrase(intent, "marcar consulta")).isFalse();
        assertThat(intent.get("trainingPhrases")).hasSize(1);
    }

    @Test
    void criaListaQuandoAIntentNaoTemFrases() throws Exception {
        ObjectNode intent = (ObjectNode) mapper.readTree("{\"displayName\":\"nova\"}");

        assertThat(DialogflowIntentsAdapter.comFrase(intent, "oi")).isTrue();
        assertThat(intent.get("trainingPhrases")).hasSize(1);
    }
}
