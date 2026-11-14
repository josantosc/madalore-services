package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.CheckinUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.service.HabilitacaoAtendimento;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Painel de chamada da sala de espera (/atendimento/painel), aberto na TV sem login. O acesso é pela chave da clínica
 * em {@code mandalore.clientes.<id>.painel.chave} (mínimo de 16 caracteres); sem chave configurada o painel não existe.
 * Mostra só o nome abreviado do paciente, o profissional e a hora da chamada.
 */
@RestController
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PainelChamadaController {

    static final int CHAVE_MIN = 16;

    private final AtendimentoUseCase atendimento;
    private final Environment ambiente;
    private final String contaPadrao;
    private final FilaAoVivo aoVivo;
    private final HabilitacaoAtendimento habilitacao;
    private final CheckinUseCase checkin;

    @Autowired
    public PainelChamadaController(AtendimentoUseCase atendimento, Environment ambiente,
                                   @Value("${agenda.conta-padrao:1}") String contaPadrao, FilaAoVivo aoVivo,
                                   HabilitacaoAtendimento habilitacao, CheckinUseCase checkin) {
        this.atendimento = atendimento;
        this.ambiente = ambiente;
        this.contaPadrao = contaPadrao == null || contaPadrao.isBlank() ? "1" : contaPadrao.trim();
        this.aoVivo = aoVivo;
        this.habilitacao = habilitacao;
        this.checkin = checkin;
    }

    public record Painel(String clinica, List<Atendimento.Chamada> chamadas) {
    }

    static final class ChaveInvalida extends RuntimeException {
        ChaveInvalida() {
            super("Painel não encontrado");
        }
    }

    @GetMapping("/atendimento/painel")
    public ResponseEntity<byte[]> pagina(@RequestParam(required = false) String conta,
                                         @RequestParam(required = false) String chave,
                                         HttpServletResponse resposta) throws IOException {
        conferir(conta(conta), chave);
        semRastro(resposta);
        try (InputStream html = new ClassPathResource("atendimento/painel.html").getInputStream()) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/atendimento/painel/api/chamadas", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Painel> chamadas(@RequestParam(required = false) String conta,
                                           @RequestParam(required = false) String chave,
                                           HttpServletResponse resposta) {
        String id = conta(conta);
        conferir(id, chave);
        semRastro(resposta);
        String clinica = ambiente.getProperty("mandalore.clientes." + id + ".nome", ambiente.getProperty("admin.nome", ""));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Painel(clinica, atendimento.chamadasDoPainel(id)));
    }

    /** QR do check-in pelo WhatsApp no canto da TV; 204 quando a clínica não configurou o número. */
    @GetMapping(value = "/atendimento/painel/api/checkin", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QrSvg.Checkin> checkin(@RequestParam(required = false) String conta,
                                                 @RequestParam(required = false) String chave,
                                                 HttpServletResponse resposta) {
        String id = conta(conta);
        conferir(id, chave);
        semRastro(resposta);
        return (checkin == null ? java.util.Optional.<Atendimento.CheckinDoDia>empty() : checkin.doDia(id))
                .map(dia -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(QrSvg.Checkin.de(dia)))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @GetMapping(value = "/atendimento/painel/api/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventos(@RequestParam(required = false) String conta,
                              @RequestParam(required = false) String chave,
                              HttpServletResponse resposta) {
        String id = conta(conta);
        conferir(id, chave);
        if (aoVivo == null) {
            throw new AtendimentoDesabilitadoException();
        }
        resposta.setHeader("X-Accel-Buffering", "no");
        resposta.setHeader("Cache-Control", "no-cache, no-transform");
        return aoVivo.assinarPainel(id);
    }

    @ExceptionHandler({ChaveInvalida.class, AtendimentoDesabilitadoException.class})
    public ResponseEntity<String> naoEncontrado(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body("Painel não encontrado. Confira o link da TV.");
    }

    private void conferir(String conta, String chave) {
        String esperada = ambiente.getProperty("mandalore.clientes." + conta + ".painel.chave", "").trim();
        if (esperada.length() < CHAVE_MIN || chave == null || !MessageDigest.isEqual(
                esperada.getBytes(StandardCharsets.UTF_8), chave.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ChaveInvalida();
        }
        if (habilitacao != null) {
            habilitacao.exigir(conta);
        }
    }

    private String conta(String conta) {
        return conta == null || conta.isBlank() ? contaPadrao : conta.trim();
    }

    /** A chave vai na URL: não pode vazar por Referer nem cair em buscador. */
    private static void semRastro(HttpServletResponse resposta) {
        resposta.setHeader("Referrer-Policy", "no-referrer");
        resposta.setHeader("X-Robots-Tag", "noindex, nofollow");
    }
}
