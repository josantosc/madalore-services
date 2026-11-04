package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.simulador.SimuladorChatwootResponse;
import br.com.mandalore.agendamentoconversa.adapter.in.web.simulador.SimuladorRequest;
import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.SimularChatwootUseCase;

@RestController
@ConditionalOnProperty(name = "simulador.enabled", havingValue = "true")
public class SimuladorChatwootController {

    private static final Logger LOG = LoggerFactory.getLogger(SimuladorChatwootController.class);

    private final SimularChatwootUseCase simular;
    private final String baseUrl;
    private final String accountId;

    public SimuladorChatwootController(
            SimularChatwootUseCase simular,
            @Value("${chatwoot.base-url:}") String baseUrl,
            @Value("${chatwoot.account-id:}") String accountId
    ) {
        this.simular = simular;
        this.baseUrl = baseUrl;
        this.accountId = accountId;
    }

    @PostMapping(
            value = "/simulador/chatwoot/mensagem",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> mensagem(@RequestBody SimuladorRequest request) {
        if (request == null || request.sessao() == null || request.sessao().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "sessao obrigatoria"));
        }
        if (request.texto() == null || request.texto().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "texto obrigatorio"));
        }
        try {
            return ResponseEntity.ok(resposta(simular.enviar(request.sessao(), request.texto())));
        } catch (RuntimeException ex) {
            LOG.warn("simulador falhou ao enviar ao Chatwoot", ex);
            return falha(ex);
        }
    }

    @GetMapping(value = "/simulador/chatwoot/conversa", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> conversa(@RequestParam("sessao") String sessao) {
        try {
            return ResponseEntity.ok(resposta(simular.ler(sessao)));
        } catch (RuntimeException ex) {
            LOG.warn("simulador falhou ao ler o Chatwoot", ex);
            return falha(ex);
        }
    }

    private SimuladorChatwootResponse resposta(ConversaVisitante conversa) {
        return SimuladorChatwootResponse.de(conversa, baseUrl, accountId);
    }

    private ResponseEntity<Map<String, String>> falha(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("erro", "Falha ao falar com o Chatwoot: " + ex.getMessage()));
    }
}
