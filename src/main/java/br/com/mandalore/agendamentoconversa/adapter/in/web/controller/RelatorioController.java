package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAuthFilter;
import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.RelatorioUseCase;

/** Prévia e envio imediato dos relatórios, dentro da curadoria (mesmo login). */
@RestController
@ConditionalOnProperty(name = {"relatorios.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class RelatorioController {

    private static final Logger LOG = LoggerFactory.getLogger(RelatorioController.class);

    private final RelatorioUseCase relatorios;

    public RelatorioController(RelatorioUseCase relatorios) {
        this.relatorios = relatorios;
    }

    @GetMapping(value = "/curadoria/api/relatorios/{tipo}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> previa(@PathVariable String tipo) {
        Relatorio.Tipo escolhido = tipo(tipo);
        if (escolhido == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Tipo de relatório inválido"));
        }
        return ResponseEntity.ok(Map.of("texto", relatorios.texto(escolhido)));
    }

    @PostMapping(value = "/curadoria/api/relatorios/{tipo}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> enviar(
            @PathVariable String tipo,
            @RequestAttribute(AdminAuthFilter.USUARIO) String usuario
    ) {
        Relatorio.Tipo escolhido = tipo(tipo);
        if (escolhido == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Tipo de relatório inválido"));
        }
        try {
            relatorios.enviar(escolhido);
            LOG.info("relatorio tipo={} status=enviado usuario={}", escolhido, usuario);
            return ResponseEntity.ok(Map.of("enviado", true));
        } catch (RuntimeException ex) {
            LOG.warn("relatorio tipo={} status=falha usuario={} motivo={}", escolhido, usuario, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("erro", ex.getMessage()));
        }
    }

    private static Relatorio.Tipo tipo(String tipo) {
        try {
            return Relatorio.Tipo.valueOf(tipo.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
