package br.com.mandalore.agendamentoconversa.adapter.in.web.precos;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** Páginas da tabela de preços: lista, login e logout. */
@RestController
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PrecosPaginaController {

    static final Duration SESSAO_INATIVA = Duration.ofHours(8);

    private static final Logger LOG = LoggerFactory.getLogger(PrecosPaginaController.class);

    private final PrecosAcesso acesso;
    private final String chatwootUrl;

    public PrecosPaginaController(PrecosAcesso acesso, @Value("${precos.chatwoot-url:}") String chatwootUrl) {
        this.acesso = acesso;
        String url = chatwootUrl == null ? "" : chatwootUrl.trim().replaceAll("/+$", "");
        this.chatwootUrl = url.matches("https?://[A-Za-z0-9.:-]+") ? url : "";
    }

    @GetMapping("/precos")
    public ResponseEntity<byte[]> lista() throws IOException {
        return pagina("precos/index.html");
    }

    @GetMapping("/precos/login")
    public ResponseEntity<byte[]> login(HttpServletRequest request) throws IOException {
        HttpSession sessao = request.getSession(false);
        if (sessao != null && sessao.getAttribute(PrecosAuthFilter.SESSAO) != null) {
            return ir("/precos");
        }
        return pagina("precos/login.html");
    }

    @PostMapping(value = "/precos/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<byte[]> entrar(
            @RequestParam(defaultValue = "") String usuario,
            @RequestParam(defaultValue = "") String senha,
            HttpServletRequest request
    ) {
        String ip = PrecosAcesso.ip(request);
        if (acesso.bloqueado(ip)) {
            return ir("/precos/login?erro=bloqueado");
        }
        if (!acesso.confere(usuario, senha)) {
            acesso.registrarFalha(ip);
            LOG.warn("tabela de precos login recusado ip={}", ip);
            return ir(acesso.bloqueado(ip) ? "/precos/login?erro=bloqueado" : "/precos/login?erro=senha");
        }
        acesso.limpar(ip);
        HttpSession anterior = request.getSession(false);
        if (anterior != null) {
            anterior.invalidate();
        }
        HttpSession sessao = request.getSession(true);
        sessao.setAttribute(PrecosAuthFilter.SESSAO, usuario.trim());
        sessao.setMaxInactiveInterval((int) SESSAO_INATIVA.toSeconds());
        LOG.info("tabela de precos login usuario={} ip={}", usuario.trim(), ip);
        return ir("/precos");
    }

    @PostMapping("/precos/logout")
    public ResponseEntity<byte[]> sair(HttpServletRequest request) {
        HttpSession sessao = request.getSession(false);
        if (sessao != null) {
            sessao.invalidate();
        }
        return ir("/precos/login?saiu=1");
    }

    private ResponseEntity<byte[]> pagina(String recurso) throws IOException {
        try (InputStream html = new ClassPathResource(recurso).getInputStream()) {
            String conteudo = new String(html.readAllBytes(), StandardCharsets.UTF_8).replace("__CHATWOOT_URL__", chatwootUrl);
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(conteudo.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static ResponseEntity<byte[]> ir(String destino) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(destino)).build();
    }
}
