package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.util.Locale;
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
import org.springframework.web.multipart.MultipartFile;

import br.com.mandalore.agendamentoconversa.adapter.in.web.simulador.SimuladorChatwootResponse;
import br.com.mandalore.agendamentoconversa.adapter.in.web.simulador.SimuladorRequest;
import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.SimularChatwootUseCase;

@RestController
@ConditionalOnProperty(name = "simulador.enabled", havingValue = "true")
public class SimuladorChatwootController {

    private static final Logger LOG = LoggerFactory.getLogger(SimuladorChatwootController.class);
    private static final long LIMITE_ARQUIVO = 10L * 1_048_576;

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

    @PostMapping(
            value = "/simulador/chatwoot/arquivo",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> arquivo(
            @RequestParam("sessao") String sessao,
            @RequestParam("arquivo") MultipartFile arquivo,
            @RequestParam(value = "texto", required = false) String texto
    ) throws IOException {
        if (sessao.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "sessao obrigatoria"));
        }
        String tipo = arquivo.getContentType() == null ? "" : arquivo.getContentType().toLowerCase(Locale.ROOT);
        if (arquivo.isEmpty() || !(tipo.startsWith("image/") || tipo.equals(MediaType.APPLICATION_PDF_VALUE))) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Envie uma foto ou um PDF"));
        }
        if (arquivo.getSize() > LIMITE_ARQUIVO) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Arquivo acima de 10 MB"));
        }
        String nome = arquivo.getOriginalFilename() == null || arquivo.getOriginalFilename().isBlank()
                ? "arquivo" : arquivo.getOriginalFilename();
        try {
            return ResponseEntity.ok(resposta(simular.enviarArquivo(
                    sessao, new ConversaVisitante.Arquivo(nome, tipo, arquivo.getBytes()), texto)));
        } catch (RuntimeException ex) {
            LOG.warn("simulador falhou ao enviar arquivo ao Chatwoot", ex);
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
