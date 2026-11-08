package br.com.mandalore.agendamentoconversa.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.qameta.allure.Allure;

import br.com.mandalore.agendamentoconversa.adapter.in.web.controller.WebhookController;
import br.com.mandalore.agendamentoconversa.adapter.out.agenda.AgendaMemoria;
import br.com.mandalore.agendamentoconversa.adapter.out.agenda.MandaloreAgendaAdapter;
import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.service.AgendaConsultaService;
import br.com.mandalore.agendamentoconversa.application.domain.service.ContextoCodec;
import br.com.mandalore.agendamentoconversa.application.domain.service.FulfillmentService;
import br.com.mandalore.agendamentoconversa.application.domain.service.IdentidadeSessao;
import br.com.mandalore.agendamentoconversa.application.domain.service.PerguntasFrequentesService;

final class ConversaRegressionRunner {

    private static final String SESSION = "projects/regression-project/agent/sessions/regression-session";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$([A-Za-z0-9_]+)");
    private static final String USUARIO = "regression";
    private static final String SENHA = "regression-secret";
    private static final Map<String, String> PERGUNTAS_FREQUENTES = Map.of(
            "endereco", "Rua Exemplo, 100 - Centro.\n",
            "horario", "Segunda a sexta, das 7h às 18h."
    );

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, ObjectNode> intentsPorNome = new LinkedHashMap<>();
    private final Map<String, ObjectNode> intentsPorEvento = new LinkedHashMap<>();
    private final Map<String, ObjectNode> contextos = new LinkedHashMap<>();
    private final FakeAgendaBackend backend = new FakeAgendaBackend();
    private final AgendaMemoria memoria = AgendaMemoria.exemplo();
    private final StringBuilder transcricao = new StringBuilder();
    private final MockMvc mvc;

    ConversaRegressionRunner() {
        this(false);
    }

    /** mandalore: o bot usa a agenda do Mandalore (em memória) em vez do backend falso do Tasy. */
    ConversaRegressionRunner(boolean mandalore) {
        carregarManifesto();
        FlowTelemetry telemetry = Mockito.mock(FlowTelemetry.class);
        MandaloreAgendaAdapter adaptador = memoria.adaptador(Clock.systemDefaultZone());
        AgendaConsultaService agenda = new AgendaConsultaService(
                mandalore ? adaptador : backend,
                mandalore ? adaptador : backend,
                new IdentidadeSessao(),
                telemetry
        );
        PerguntasFrequentesService perguntas = new PerguntasFrequentesService(
                tema -> Optional.ofNullable(PERGUNTAS_FREQUENTES.get(tema))
        );
        WebhookController controller = new WebhookController(
                new FulfillmentService(agenda, perguntas, telemetry),
                new ContextoCodec(mapper),
                telemetry,
                USUARIO,
                SENHA,
                4000
        );
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    void executar(JsonNode cenario) {
        contextos.clear();
        transcricao.setLength(0);
        backend.configurar(cenario.path("fixture"));
        Allure.epic("Fluxo conversacional");
        Allure.story(cenario.path("id").asText());
        int numero = 0;
        try {
            for (JsonNode turno : cenario.path("turnos")) {
                numero++;
                executarComPasso(cenario, numero, turno);
            }
        } finally {
            Allure.addAttachment("Conversa completa", "text/plain", transcricao.toString(), ".txt");
        }
    }

    private void executarComPasso(JsonNode cenario, int numero, JsonNode turno) {
        String titulo = "Turno %d: \"%s\" -> %s".formatted(
                numero,
                turno.path("texto").asText(""),
                turno.path("intent").asText()
        );
        try {
            Allure.step(titulo, () -> executarTurno(turno));
        } catch (AssertionError error) {
            throw new AssertionError(
                    "Cenário '%s', turno %d (%s): %s".formatted(
                            cenario.path("id").asText(),
                            numero,
                            turno.path("intent").asText(),
                            error.getMessage()
                    ),
                    error
            );
        } catch (Exception error) {
            throw new AssertionError(
                    "Cenário '%s', turno %d (%s) falhou".formatted(
                            cenario.path("id").asText(),
                            numero,
                            turno.path("intent").asText()
                    ),
                    error
            );
        }
    }

    private void executarTurno(JsonNode turno) throws Exception {
        String nomeIntent = turno.path("intent").asText();
        ObjectNode intent = intentsPorNome.get(nomeIntent);
        assertThat(intent).as("intent existente no manifesto").isNotNull();
        validarContextosEntrada(intent);

        JsonNode parametros = turno.path("parametros");
        String evento = null;
        String texto;
        if ("WEBHOOK_STATE_ENABLED".equals(intent.path("webhookState").asText())) {
            aplicarSaidas(intent, parametros);
            ObjectNode resposta = chamarWebhook(intent, turno, parametros);
            evento = resposta.path("followupEventInput").path("name").asText();
            JsonNode parametrosEvento = resposta.path("followupEventInput").path("parameters");
            aplicarRespostaWebhook(resposta.path("outputContexts"));
            ObjectNode destino = intentsPorEvento.get(evento);
            assertThat(destino).as("destino único para o evento " + evento).isNotNull();
            validarContextosEntrada(destino);
            texto = renderizar(destino, parametrosEvento);
            aplicarSaidas(destino, parametrosEvento);
        } else {
            if (intent.path("resetContexts").asBoolean(false)) {
                contextos.clear();
            }
            texto = renderizar(intent, parametros);
            aplicarSaidas(intent, parametros);
        }
        registrar(turno, evento, texto);
        validarExpectativas(turno.path("espera"), evento, texto);
    }

    private void registrar(JsonNode turno, String evento, String texto) {
        String estado = "Intent: %s%nEvento: %s%nContextos ativos: %s".formatted(
                turno.path("intent").asText(),
                evento == null ? "(sem webhook)" : evento,
                String.join(", ", contextos.keySet())
        );
        Allure.addAttachment("Resposta ao usuário", "text/plain", texto, ".txt");
        Allure.addAttachment("Estado da conversa", "text/plain", estado, ".txt");
        transcricao.append("Usuário: ").append(turno.path("texto").asText("")).append('\n')
                .append("Bot: ").append(texto.replace("\n", "\n     ")).append('\n')
                .append("     [").append(evento == null ? turno.path("intent").asText() : evento)
                .append(" | contextos: ").append(String.join(", ", contextos.keySet())).append("]\n\n");
    }

    private ObjectNode chamarWebhook(ObjectNode intent, JsonNode turno, JsonNode parametros) throws Exception {
        ObjectNode raiz = mapper.createObjectNode();
        raiz.put("session", SESSION);
        ObjectNode query = raiz.putObject("queryResult");
        query.put("queryText", turno.path("texto").asText(""));
        query.put("action", intent.path("action").asText(""));
        query.putObject("intent").put("displayName", intent.path("displayName").asText());
        query.set("parameters", parametros.isObject() ? parametros.deepCopy() : mapper.createObjectNode());
        ArrayNode ativos = query.putArray("outputContexts");
        contextos.values().forEach(ativos::add);

        String credencial = Base64.getEncoder().encodeToString(
                (USUARIO + ":" + SENHA).getBytes(StandardCharsets.UTF_8)
        );
        MockHttpServletResponse response = mvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Basic " + credencial)
                        .content(mapper.writeValueAsBytes(raiz)))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as("status do webhook").isEqualTo(200);
        String corpo = response.getContentAsString(StandardCharsets.UTF_8);
        return (ObjectNode) mapper.readTree(corpo);
    }

    private void validarExpectativas(JsonNode espera, String evento, String texto) {
        if (espera.has("evento")) {
            assertThat(evento).as("evento").isEqualTo(espera.path("evento").asText());
        } else {
            assertThat(evento).as("intent textual não deve chamar webhook").isNull();
        }
        for (JsonNode trecho : espera.path("textoContem")) {
            assertThat(texto).as("texto ao usuário").contains(trecho.asText());
        }
        for (JsonNode contexto : espera.path("contextos")) {
            assertThat(contextos.keySet()).as("contextos ativos").contains(contexto.asText());
        }
        for (JsonNode contexto : espera.path("contextosAusentes")) {
            assertThat(contextos.keySet()).as("contextos ativos").doesNotContain(contexto.asText());
        }
        espera.path("payloadContem").fields().forEachRemaining(campo -> {
            ObjectNode contexto = contextos.get(campo.getKey());
            assertThat(contexto).as("contexto " + campo.getKey()).isNotNull();
            String chave = switch (campo.getKey()) {
                case "oferta" -> "horarios";
                case "agendamentos" -> "itens";
                default -> "pagina";
            };
            assertThat(contexto.path("parameters").path(chave).asText())
                    .as("payload do contexto " + campo.getKey())
                    .contains(campo.getValue().asText());
        });
        if (espera.has("escritas")) {
            assertThat(backend.quantidadeEscritas()).as("quantidade de escritas").isEqualTo(espera.path("escritas").asInt());
        }
        if (espera.has("ultimaEscrita")) {
            assertThat(backend.escritas()).as("operações de escrita").endsWith(espera.path("ultimaEscrita").asText());
        }
        JsonNode mandalore = espera.path("agendaMandalore");
        if (mandalore.has("agendamentos")) {
            assertThat(memoria.todos()).as("agendamentos na agenda do Mandalore").hasSize(mandalore.path("agendamentos").asInt());
        }
        if (mandalore.has("pendenciasAbertas")) {
            assertThat(memoria.todasPendencias().stream().filter(p -> p.situacao() == Agenda.SituacaoPendencia.ABERTA))
                    .as("pendências abertas para o sistema da clínica").hasSize(mandalore.path("pendenciasAbertas").asInt());
        }
    }

    private String renderizar(ObjectNode intent, JsonNode parametros) {
        List<String> partes = new ArrayList<>();
        for (JsonNode mensagem : intent.path("messages")) {
            String template = mensagem.path("text").path("text").path(0).asText();
            Matcher matcher = PLACEHOLDER.matcher(template);
            StringBuffer saida = new StringBuffer();
            while (matcher.find()) {
                JsonNode valor = parametros.path(matcher.group(1));
                matcher.appendReplacement(saida, Matcher.quoteReplacement(valor.isMissingNode() ? "" : valor.asText()));
            }
            matcher.appendTail(saida);
            if (!saida.toString().isBlank()) {
                partes.add(saida.toString().trim());
            }
        }
        return String.join("\n\n", partes);
    }

    private void aplicarRespostaWebhook(JsonNode saidas) {
        for (JsonNode saida : saidas) {
            String nome = nomeContexto(saida.path("name").asText());
            if (saida.path("lifespanCount").asInt() == 0) {
                contextos.remove(nome);
            } else {
                contextos.put(nome, (ObjectNode) saida.deepCopy());
            }
        }
    }

    /** Como o Dialogflow ES: mescla os parâmetros e grava cada parâmetro declarado da intent em todos os contextos ativos. */
    private void aplicarSaidas(ObjectNode intent, JsonNode parametros) {
        for (JsonNode saida : intent.path("outputContexts")) {
            String nome = nomeContexto(saida.path("name").asText());
            int lifespan = saida.path("lifespanCount").asInt();
            if (lifespan == 0) {
                contextos.remove(nome);
                continue;
            }
            ObjectNode contexto = mapper.createObjectNode();
            contexto.put("name", SESSION + "/contexts/" + nome);
            contexto.put("lifespanCount", lifespan);
            ObjectNode parametrosContexto = contexto.putObject("parameters");
            ObjectNode anterior = contextos.get(nome);
            if (anterior != null && anterior.path("parameters").isObject()) {
                parametrosContexto.setAll((ObjectNode) anterior.path("parameters"));
            }
            contextos.put(nome, contexto);
        }
        for (JsonNode declarado : intent.path("parameters")) {
            String chave = declarado.path("displayName").asText();
            JsonNode valor = parametros.path(chave);
            JsonNode gravado = valor.isMissingNode() || valor.isNull() ? mapper.getNodeFactory().textNode("") : valor;
            for (ObjectNode contexto : contextos.values()) {
                ObjectNode parametrosContexto = contexto.has("parameters") && contexto.path("parameters").isObject()
                        ? (ObjectNode) contexto.path("parameters")
                        : contexto.putObject("parameters");
                parametrosContexto.set(chave, gravado);
            }
        }
    }

    private void validarContextosEntrada(ObjectNode intent) {
        List<String> faltantes = new ArrayList<>();
        for (JsonNode entrada : intent.path("inputContextNames")) {
            String nome = nomeContexto(entrada.asText());
            if (!contextos.containsKey(nome)) {
                faltantes.add(nome);
            }
        }
        assertThat(faltantes)
                .as("contextos requeridos por " + intent.path("displayName").asText())
                .isEmpty();
    }

    private void carregarManifesto() {
        try (InputStream input = recurso("dialogflow/agent-manifest.json")) {
            JsonNode manifesto = mapper.readTree(input);
            for (JsonNode node : manifesto.path("intents")) {
                ObjectNode intent = (ObjectNode) node;
                String nome = intent.path("displayName").asText();
                if (intentsPorNome.put(nome, intent) != null) {
                    fail("Intent duplicada no manifesto: " + nome);
                }
                for (JsonNode evento : intent.path("events")) {
                    String nomeEvento = evento.asText();
                    if (intentsPorEvento.put(nomeEvento, intent) != null) {
                        fail("Evento com mais de um destino no manifesto: " + nomeEvento);
                    }
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Não foi possível carregar o manifesto Dialogflow", error);
        }
    }

    private InputStream recurso(String nome) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(nome);
        if (input == null) {
            throw new IllegalStateException("Recurso não encontrado: " + nome);
        }
        return input;
    }

    private String nomeContexto(String caminho) {
        int indice = caminho.lastIndexOf('/');
        return indice < 0 ? caminho : caminho.substring(indice + 1);
    }
}
