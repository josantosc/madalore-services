package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.simulador.SimuladorRequest;
import br.com.mandalore.agendamentoconversa.adapter.in.web.simulador.SimuladorResponse;
import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.SimularConversaUseCase;

@RestController
@ConditionalOnProperty(name = "simulador.enabled", havingValue = "true")
public class SimuladorController {

    private static final Logger LOG = LoggerFactory.getLogger(SimuladorController.class);

    private final SimularConversaUseCase simular;

    public SimuladorController(SimularConversaUseCase simular) {
        this.simular = simular;
    }

    @GetMapping("/simulador")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("simulador/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @PostMapping(
            value = "/simulador/mensagem",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> mensagem(@RequestBody SimuladorRequest request) {
        if (request == null || request.sessao() == null || request.sessao().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "sessao obrigatoria"));
        }
        long inicio = System.nanoTime();
        try {
            RespostaDialogflow resposta = simular.enviar(request.sessao(), request.texto());
            return ResponseEntity.ok(SimuladorResponse.de(resposta, (System.nanoTime() - inicio) / 1_000_000));
        } catch (RuntimeException ex) {
            LOG.warn("simulador falhou ao consultar o Dialogflow", ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("erro", "Falha ao consultar o Dialogflow: " + ex.getMessage()));
        }
    }
}
