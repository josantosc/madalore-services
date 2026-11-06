package br.com.mandalore.agendamentoconversa.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.stream.StreamSupport;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ConversaRegressionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> jornadasConversacionais() throws Exception {
        JsonNode indice = ler("conversas/index.json");
        assertThat(indice.isArray()).isTrue();
        return StreamSupport.stream(indice.spliterator(), false).map(item -> {
            String recurso = "conversas/" + item.asText();
            return DynamicTest.dynamicTest(item.asText(), () -> {
                JsonNode cenario = ler(recurso);
                new ConversaRegressionRunner("mandalore".equals(cenario.path("agenda").asText())).executar(cenario);
            });
        });
    }

    private JsonNode ler(String recurso) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(input).as("recurso " + recurso).isNotNull();
            return mapper.readTree(input);
        }
    }
}
