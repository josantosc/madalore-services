package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberMensagemUseCase;

@RestController
public class ChatwootController {

    private final ReceberMensagemUseCase receberMensagem;
    private final String segredoGlobal;
    private final ClientesProperties clientes;

    public ChatwootController(
            ReceberMensagemUseCase receberMensagem,
            @Value("${chatwoot.webhook-secret:}") String segredo,
            ClientesProperties clientes
    ) {
        this.receberMensagem = receberMensagem;
        this.segredoGlobal = segredo;
        this.clientes = clientes;
    }

    @PostMapping("/chatwoot")
    public ResponseEntity<Void> receber(
            @RequestParam(name = "token", required = false) String tokenQuery,
            @RequestHeader(name = "token", required = false) String tokenHeader,
            @RequestHeader(name = "X-Chatwoot-Token", required = false) String tokenAlternativo,
            @RequestBody JsonNode corpo
    ) {
        String informado = primeiro(tokenQuery, tokenHeader, tokenAlternativo);
        MensagemEntrada mensagem = ler(corpo);
        if (!segredoConfere(segredo(mensagem.accountId()), informado)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        receberMensagem.receber(mensagem);
        return ResponseEntity.ok().build();
    }

    private String segredo(String accountId) {
        return clientes.cliente(accountId)
                .map(ClientesProperties.Cliente::webhookSecret)
                .filter(segredo -> !segredo.isBlank())
                .orElse(segredoGlobal);
    }

    private MensagemEntrada ler(JsonNode corpo) {
        JsonNode conversation = corpo.path("conversation");
        Canal canal = Canal.doChatwoot(
                texto(conversation, "channel"),
                texto(conversation.path("additional_attributes"), "type")
        );
        String messageType = texto(corpo, "message_type");
        if (messageType == null && corpo.path("message_type").isNumber()) {
            messageType = corpo.path("message_type").asText();
        }
        return new MensagemEntrada(
                texto(corpo, "event"),
                messageType,
                corpo.path("private").asBoolean(false),
                texto(corpo, "content"),
                corpo.path("id").isMissingNode() ? null : corpo.path("id").asText(),
                corpo.path("account").path("id").asText(null),
                conversation.path("id").asText(null),
                conversation.path("status").asText(null),
                canal
        );
    }

    private boolean segredoConfere(String segredo, String informado) {
        if (segredo == null || segredo.isBlank() || informado == null) {
            return false;
        }
        return MessageDigest.isEqual(
                segredo.getBytes(StandardCharsets.UTF_8),
                informado.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String primeiro(String... valores) {
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor;
            }
        }
        return null;
    }

    private String texto(JsonNode node, String campo) {
        JsonNode valor = node.path(campo);
        if (valor.isMissingNode() || valor.isNull()) {
            return null;
        }
        return valor.asText();
    }
}
