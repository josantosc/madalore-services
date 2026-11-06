package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;
import br.com.mandalore.agendamentoconversa.application.domain.model.FulfillmentCommand;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoOperacao;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ExecutarFulfillmentUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.service.ContextoCodec;
import br.com.mandalore.agendamentoconversa.application.domain.service.Textos;

@RestController
public class WebhookController {

    private final ExecutarFulfillmentUseCase fulfillment;
    private final ContextoCodec codec;
    private final FlowTelemetry telemetry;
    private final String usuario;
    private final String senha;

    public WebhookController(
            ExecutarFulfillmentUseCase fulfillment,
            ContextoCodec codec,
            FlowTelemetry telemetry,
            @Value("${dialogflow.webhook-user:}") String usuario,
            @Value("${dialogflow.webhook-password:}") String senha
    ) {
        this.fulfillment = fulfillment;
        this.codec = codec;
        this.telemetry = telemetry;
        this.usuario = usuario;
        this.senha = senha;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> receber(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody JsonNode corpo
    ) {
        long inicio = System.nanoTime();
        JsonNode query = corpo.path("queryResult");
        String session = corpo.path("session").asText("");
        String conversationId = Textos.conversationId(session);
        String intent = query.path("intent").path("displayName").asText("");
        String action = query.path("action").asText("");
        if (!autorizado(authorization)) {
            telemetry.record(FlowEvent.builder("DIALOGFLOW", "fulfillment.authentication", "ERROR")
                    .correlation(conversationId, conversationId)
                    .intent(intent, action)
                    .httpStatus(HttpStatus.UNAUTHORIZED.value())
                    .error("INVALID_BASIC_AUTH", "SecurityException", "Credencial do webhook inválida")
                    .durationMs(elapsed(inicio))
                    .build());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        FulfillmentCommand command = new FulfillmentCommand(
                session,
                intent,
                query.path("queryText").asText(""),
                parametros(query.path("parameters")),
                codec.ler(query.path("outputContexts"))
        );
        if (action.isBlank()) {
            action = command.operacao();
        }
        telemetry.record(FlowEvent.builder("DIALOGFLOW", "fulfillment.received", "SUCCESS")
                .correlation(conversationId, conversationId)
                .intent(intent, action)
                .httpStatus(HttpStatus.OK.value())
                .build());
        ResultadoOperacao resultado = ClienteAtual.executar(
                Textos.accountId(session),
                Textos.accountId(session) == null ? null : Textos.conversationId(session),
                () -> fulfillment.executar(command)
        );
        telemetry.record(FlowEvent.builder("DIALOGFLOW", "fulfillment.response", "SUCCESS")
                .correlation(conversationId, conversationId)
                .intent(intent, action)
                .eventName(resultado.evento())
                .businessResult(resultado.codigo().valor())
                .httpStatus(HttpStatus.OK.value())
                .durationMs(elapsed(inicio))
                .build());
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("name", resultado.evento());
        evento.put("languageCode", "pt-BR");
        List<Map<String, Object>> contextos = codec.escrever(session, resultado.contexto());
        Map<String, Object> parametrosEvento = new LinkedHashMap<>(resultado.parametros());
        incluirPayloadContexto(resultado.evento(), contextos, parametrosEvento);
        evento.put("parameters", parametrosEvento);
        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("followupEventInput", evento);
        resposta.put("outputContexts", contextos);
        return ResponseEntity.ok(resposta);
    }

    private void incluirPayloadContexto(
            String evento,
            List<Map<String, Object>> contextos,
            Map<String, Object> parametrosEvento
    ) {
        String contextoAlvo = "RESULTADO_LISTA_HORARIOS".equals(evento) ? "oferta" : "lista";
        for (Map<String, Object> contexto : contextos) {
            String nome = String.valueOf(contexto.get("name"));
            if (!nome.endsWith("/contexts/" + contextoAlvo)) {
                continue;
            }
            Object parametros = contexto.get("parameters");
            if (parametros instanceof Map<?, ?> mapa && mapa.get("payload") != null) {
                parametrosEvento.put("payload", mapa.get("payload"));
                return;
            }
        }
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private Map<String, Object> parametros(JsonNode node) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return mapa;
        }
        node.fields().forEachRemaining(campo -> mapa.put(campo.getKey(), valor(campo.getValue())));
        return mapa;
    }

    private Object valor(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.toString();
    }

    private boolean autorizado(String authorization) {
        if (usuario == null || usuario.isBlank() || senha == null || senha.isBlank() || authorization == null) {
            return false;
        }
        if (!authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8
            );
            int separador = decoded.indexOf(':');
            if (separador < 0) {
                return false;
            }
            return igual(usuario, decoded.substring(0, separador)) && igual(senha, decoded.substring(separador + 1));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean igual(String esperado, String informado) {
        return MessageDigest.isEqual(
                esperado.getBytes(StandardCharsets.UTF_8),
                informado.getBytes(StandardCharsets.UTF_8)
        );
    }
}
