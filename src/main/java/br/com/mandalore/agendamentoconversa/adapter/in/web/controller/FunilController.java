package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAcesso;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAuthFilter;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;

/** API da tela do funil de orçamentos (/funil). Cada login enxerga só as clínicas dele. */
@RestController
@ConditionalOnProperty(name = {"funil.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class FunilController {

    private final FunilUseCase funil;

    public FunilController(FunilUseCase funil) {
        this.funil = funil;
    }

    public record EtapaRequest(Funil.Etapa etapa, Funil.MotivoPerda motivo) {
    }

    public record RetornoRequest(LocalDate data) {
    }

    public record ValorRequest(BigDecimal valor) {
    }

    static final class Proibido extends RuntimeException {
        Proibido() {
            super("Seu usuário não tem acesso a esta clínica");
        }
    }

    @GetMapping("/funil")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("funil/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/funil/api/oportunidades", produces = MediaType.APPLICATION_JSON_VALUE)
    public FunilUseCase.Painel painel(@RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return funil.painel(login.contas());
    }

    @PostMapping(value = "/funil/api/oportunidades/{id}/etapa", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> etapa(@PathVariable long id, @RequestBody EtapaRequest request,
                                      @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        conferir(id, login);
        funil.mudarEtapa(id, request.etapa(), request.motivo(), login.usuario());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/funil/api/oportunidades/{id}/retorno", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> retorno(@PathVariable long id, @RequestBody RetornoRequest request,
                                        @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        conferir(id, login);
        funil.adiarRetorno(id, request.data(), login.usuario());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/funil/api/oportunidades/{id}/valor", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> valor(@PathVariable long id, @RequestBody ValorRequest request,
                                      @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        conferir(id, login);
        funil.definirValor(id, request.valor(), login.usuario());
        return ResponseEntity.noContent().build();
    }

    private void conferir(long id, AdminAcesso.Login login) {
        if (!login.podeVer(funil.buscar(id).conta())) {
            throw new Proibido();
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalido(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflito(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", ex.getMessage()));
    }

    @ExceptionHandler(Proibido.class)
    public ResponseEntity<Map<String, Object>> proibido(Proibido ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ex.getMessage()));
    }
}
