package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAuthFilter;
import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.CuradoriaUseCase;

@RestController
@ConditionalOnProperty(name = {"curadoria.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class CuradoriaController {

    private static final Logger LOG = LoggerFactory.getLogger(CuradoriaController.class);

    private final CuradoriaUseCase curadoria;

    public CuradoriaController(CuradoriaUseCase curadoria) {
        this.curadoria = curadoria;
    }

    public record DecisaoRequest(String texto, Curadoria.Acao acao, String intent, String frase) {
    }

    @GetMapping("/curadoria")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("curadoria/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/curadoria/api/indicadores", produces = MediaType.APPLICATION_JSON_VALUE)
    public Curadoria.Indicadores indicadores(@RequestParam(defaultValue = "7") int dias) {
        return curadoria.indicadores(dias);
    }

    @GetMapping(value = "/curadoria/api/fila", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Curadoria.Frase> fila(@RequestParam(defaultValue = "7") int dias) {
        return curadoria.fila(dias);
    }

    @GetMapping(value = "/curadoria/api/intents", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> intents() {
        try {
            return ResponseEntity.ok(curadoria.intents());
        } catch (RuntimeException ex) {
            return falhaDialogflow("listar intents", ex);
        }
    }

    @PostMapping(value = "/curadoria/api/decisao", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> decidir(
            @RequestBody DecisaoRequest request,
            @RequestAttribute(AdminAuthFilter.USUARIO) String usuario
    ) {
        try {
            boolean nova = curadoria.decidir(new Curadoria.Decisao(
                    request.texto(), request.acao(), request.intent(), request.frase(), usuario));
            LOG.info("curadoria decisao acao={} intent={} usuario={} frase_nova={}",
                    request.acao(), request.intent(), usuario, nova);
            return ResponseEntity.ok(Map.of("fraseNova", nova));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
        } catch (RuntimeException ex) {
            return falhaDialogflow("ensinar", ex);
        }
    }

    private ResponseEntity<Map<String, String>> falhaDialogflow(String etapa, RuntimeException ex) {
        LOG.warn("curadoria falha ao {} no Dialogflow", etapa, ex);
        String detalhe = ex instanceof RestClientResponseException http && http.getStatusCode().value() == 403
                ? "a conta de serviço precisa do papel Dialogflow API Admin"
                : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("erro", "Falha ao " + etapa + " no Dialogflow: " + detalhe));
    }
}
