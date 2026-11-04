package br.com.mandalore.agendamentoconversa.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class DialogflowContractTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$([A-Za-z0-9_]+)");
    private static final Set<String> OPERACOES = Set.of(
            "identificar",
            "sem_cadastro",
            "convenios",
            "profissionais",
            "especialidades",
            "exames",
            "escolher_lista",
            "disponibilidade",
            "meus_agendamentos",
            "escolher",
            "agendar",
            "confirmar",
            "cancelar",
            "remarcar"
    );
    private static final Set<String> EVENTOS_BACKEND = Set.of(
            "RESULTADO_PACIENTE_NAO_ENCONTRADO",
            "RESULTADO_NAO_IDENTIFICADO",
            "RESULTADO_SUCESSO_IDENTIFICAR",
            "RESULTADO_JA_TEM_AGENDAMENTO",
            "RESULTADO_TIPO_BUSCA",
            "RESULTADO_LISTA_CONVENIO",
            "RESULTADO_LISTA_PROFISSIONAL",
            "RESULTADO_UMA_ESPECIALIDADE",
            "RESULTADO_LISTA_ESPECIALIDADE",
            "RESULTADO_LISTA_EXAME",
            "RESULTADO_SEM_EXAME",
            "RESULTADO_SEM_HORARIO_EXAME",
            "RESULTADO_PERIODO",
            "RESULTADO_LISTA_HORARIOS",
            "RESULTADO_SEM_HORARIO",
            "RESULTADO_SEM_AGENDAMENTO",
            "RESULTADO_SUCESSO_LISTA",
            "RESULTADO_CONFIRMACAO_AUSENTE",
            "RESULTADO_HORARIO_FORA_DA_OFERTA",
            "RESULTADO_HORARIO_INDISPONIVEL",
            "RESULTADO_AGENDAMENTO_NAO_ENCONTRADO",
            "RESULTADO_SUCESSO_ESCRITA",
            "RESULTADO_INDISPONIVEL"
    );

    private static JsonNode manifesto;

    @BeforeAll
    static void carregar() throws Exception {
        try (InputStream input = DialogflowContractTest.class.getClassLoader()
                .getResourceAsStream("dialogflow/agent-manifest.json")) {
            assertThat(input).isNotNull();
            manifesto = new ObjectMapper().readTree(input);
        }
    }

    @Test
    void todaAcaoDoAgenteMapeiaOperacaoDoBackend() {
        for (JsonNode intent : manifesto.path("intents")) {
            String nome = intent.path("displayName").asText();
            if (!nome.startsWith("acao.")) {
                continue;
            }
            assertThat(intent.path("webhookState").asText())
                    .as(nome + " deve chamar webhook")
                    .isEqualTo("WEBHOOK_STATE_ENABLED");
            String operacao = intent.path("action").asText();
            if (operacao.isBlank()) {
                operacao = nome.substring(nome.lastIndexOf('.') + 1);
            }
            assertThat(OPERACOES).as("operação de " + nome).contains(operacao);
        }
    }

    @Test
    void todoEventoDoBackendPossuiUmUnicoDestino() {
        Map<String, String> destinos = new HashMap<>();
        Set<String> duplicados = new HashSet<>();
        for (JsonNode intent : manifesto.path("intents")) {
            for (JsonNode event : intent.path("events")) {
                String anterior = destinos.put(event.asText(), intent.path("displayName").asText());
                if (anterior != null) {
                    duplicados.add(event.asText());
                }
            }
        }
        assertThat(duplicados).as("eventos com destinos duplicados").isEmpty();
        assertThat(destinos.keySet()).containsAll(EVENTOS_BACKEND);
    }

    @Test
    void placeholdersDasMensagensSaoParametrosDeclarados() {
        for (JsonNode intent : manifesto.path("intents")) {
            Set<String> parametros = new HashSet<>();
            intent.path("parameters").forEach(item -> parametros.add(item.path("displayName").asText()));
            for (JsonNode message : intent.path("messages")) {
                for (JsonNode texto : message.path("text").path("text")) {
                    Matcher matcher = PLACEHOLDER.matcher(texto.asText());
                    while (matcher.find()) {
                        assertThat(parametros)
                                .as("parâmetro $" + matcher.group(1) + " em " + intent.path("displayName").asText())
                                .contains(matcher.group(1));
                    }
                }
            }
        }
    }

    @Test
    void contextosPossuemNomeELifespanValido() {
        for (JsonNode intent : manifesto.path("intents")) {
            for (JsonNode contexto : intent.path("outputContexts")) {
                assertThat(contexto.path("name").asText()).contains("/contexts/");
                assertThat(contexto.path("lifespanCount").asInt()).isGreaterThanOrEqualTo(0);
            }
        }
    }
}
