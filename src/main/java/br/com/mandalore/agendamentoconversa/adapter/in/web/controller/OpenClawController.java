package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.RelatorioUseCase;

/**
 * Leitura dos relatórios pelo OpenClaw, com o token de OPENCLAW_TOKEN no cabeçalho
 * {@code Authorization: Bearer}. Só leitura e só números: o OpenClaw agenda e envia ao Telegram.
 */
@RestController
@ConditionalOnProperty(name = {"relatorios.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class OpenClawController {

    private static final Logger LOG = LoggerFactory.getLogger(OpenClawController.class);
    private static final String BEARER = "Bearer ";

    private final RelatorioUseCase relatorios;
    private final String token;

    public OpenClawController(RelatorioUseCase relatorios, @Value("${openclaw.token:}") String token) {
        this.relatorios = relatorios;
        this.token = token == null ? "" : token.trim();
    }

    @GetMapping(value = "/openclaw/relatorios/{tipo}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> relatorio(
            @PathVariable String tipo,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        ResponseEntity<Map<String, Object>> recusa = recusa(authorization);
        if (recusa != null) {
            return recusa;
        }
        Relatorio.Tipo escolhido;
        try {
            escolhido = Relatorio.Tipo.valueOf(tipo.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return erro(HttpStatus.BAD_REQUEST, "Tipo de relatório inválido: use curadoria, conversao ou gestor");
        }
        return ResponseEntity.ok(Map.of("tipo", escolhido.name().toLowerCase(Locale.ROOT), "texto", relatorios.texto(escolhido)));
    }

    /** {@code de} e {@code ate} em AAAA-MM-DD, os dois inclusive; sem {@code ate}, só o dia {@code de}. */
    @GetMapping(value = "/openclaw/numeros", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> numeros(
            @RequestParam String de,
            @RequestParam(required = false) String ate,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        ResponseEntity<Map<String, Object>> recusa = recusa(authorization);
        if (recusa != null) {
            return recusa;
        }
        try {
            LocalDate inicio = LocalDate.parse(de);
            Relatorio.Periodo periodo = relatorios.periodo(inicio, ate == null || ate.isBlank() ? inicio : LocalDate.parse(ate));
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("de", periodo.de().toString());
            corpo.put("ate", periodo.ate().toString());
            corpo.put("numeros", periodo.numeros());
            return ResponseEntity.ok(corpo);
        } catch (DateTimeParseException ex) {
            return erro(HttpStatus.BAD_REQUEST, "Data inválida: use AAAA-MM-DD");
        } catch (IllegalArgumentException ex) {
            return erro(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> recusa(String authorization) {
        if (token.isEmpty()) {
            return erro(HttpStatus.SERVICE_UNAVAILABLE, "Defina OPENCLAW_TOKEN no Mandalore");
        }
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        authorization.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("openclaw acesso recusado");
            return erro(HttpStatus.UNAUTHORIZED, "Token inválido");
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> erro(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).body(Map.of("erro", mensagem));
    }
}
