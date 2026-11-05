package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** Central de administração: login único, página inicial e o menu lateral comum às telas. */
@RestController
public class AdminController {

    static final Duration SESSAO_INATIVA = Duration.ofHours(8);
    private static final Pattern VOLTA = Pattern.compile("/(admin|curadoria|precos|simulador)([/?][A-Za-z0-9/?&=._%-]*)?");

    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    private final AdminAcesso acesso;
    private final String nome;
    private final String chatwootUrl;

    public AdminController(
            AdminAcesso acesso,
            @Value("${admin.nome:}") String nome,
            @Value("${admin.chatwoot-url:}") String chatwootUrl
    ) {
        this.acesso = acesso;
        this.nome = nome == null || nome.isBlank() ? "Central" : nome.trim();
        String url = chatwootUrl == null ? "" : chatwootUrl.trim().replaceAll("/+$", "");
        this.chatwootUrl = url.matches("https?://[A-Za-z0-9.:-]+") ? url : "";
    }

    public record ModuloResposta(String id, String titulo, String descricao, String caminho, boolean ativo) {
    }

    public record EuResposta(String usuario, String nome, String chatwootUrl, List<ModuloResposta> modulos) {
    }

    @GetMapping("/admin")
    public ResponseEntity<byte[]> central() throws IOException {
        return html("admin/index.html");
    }

    @GetMapping("/admin/login")
    public ResponseEntity<byte[]> login(HttpServletRequest request) throws IOException {
        HttpSession sessao = request.getSession(false);
        if (sessao != null && sessao.getAttribute(AdminAuthFilter.SESSAO) != null) {
            return ir(destino(request.getParameter("volta")));
        }
        return html("admin/login.html");
    }

    @PostMapping(value = "/admin/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<byte[]> entrar(
            @RequestParam(defaultValue = "") String usuario,
            @RequestParam(defaultValue = "") String senha,
            @RequestParam(defaultValue = "") String volta,
            HttpServletRequest request
    ) {
        String ip = AdminAcesso.ip(request);
        String sufixo = volta.isBlank() ? "" : "&volta=" + URLEncoder.encode(volta, StandardCharsets.UTF_8);
        if (acesso.bloqueado(ip)) {
            return ir("/admin/login?erro=bloqueado" + sufixo);
        }
        Optional<AdminAcesso.Login> login = acesso.autenticar(usuario, senha);
        if (login.isEmpty()) {
            acesso.registrarFalha(ip);
            LOG.warn("admin login recusado ip={}", ip);
            return ir("/admin/login?erro=" + (acesso.bloqueado(ip) ? "bloqueado" : "senha") + sufixo);
        }
        acesso.limpar(ip);
        HttpSession anterior = request.getSession(false);
        if (anterior != null) {
            anterior.invalidate();
        }
        HttpSession sessao = request.getSession(true);
        sessao.setAttribute(AdminAuthFilter.SESSAO, login.get());
        sessao.setMaxInactiveInterval((int) SESSAO_INATIVA.toSeconds());
        LOG.info("admin login usuario={} modulos={} ip={}", login.get().usuario(), login.get().modulos(), ip);
        return ir(destino(volta));
    }

    @PostMapping("/admin/logout")
    public ResponseEntity<byte[]> sair(HttpServletRequest request) {
        HttpSession sessao = request.getSession(false);
        if (sessao != null) {
            sessao.invalidate();
        }
        return ir("/admin/login?saiu=1");
    }

    @GetMapping(value = "/admin/api/eu", produces = MediaType.APPLICATION_JSON_VALUE)
    public EuResposta eu(@RequestAttribute(AdminAuthFilter.USUARIO) String usuario, HttpServletRequest request) {
        HttpSession sessao = request.getSession(false);
        Object login = sessao == null ? null : sessao.getAttribute(AdminAuthFilter.SESSAO);
        List<ModuloResposta> modulos = Arrays.stream(Modulo.values())
                .filter(m -> !(login instanceof AdminAcesso.Login l) || l.modulos().contains(m))
                .map(m -> new ModuloResposta(m.name().toLowerCase(), m.titulo(), m.descricao(), m.caminho(), acesso.ativo(m)))
                .toList();
        return new EuResposta(usuario, nome, chatwootUrl, modulos);
    }

    @GetMapping("/admin/assets/{arquivo:nav\\.(?:js|css)}")
    public ResponseEntity<byte[]> asset(@PathVariable String arquivo) throws IOException {
        MediaType tipo = arquivo.endsWith(".js")
                ? new MediaType("text", "javascript", StandardCharsets.UTF_8)
                : new MediaType("text", "css", StandardCharsets.UTF_8);
        try (InputStream conteudo = new ClassPathResource("admin/" + arquivo).getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(tipo)
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                    .body(conteudo.readAllBytes());
        }
    }

    private ResponseEntity<byte[]> html(String recurso) throws IOException {
        try (InputStream html = new ClassPathResource(recurso).getInputStream()) {
            String conteudo = new String(html.readAllBytes(), StandardCharsets.UTF_8).replace("__CHATWOOT_URL__", chatwootUrl);
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(conteudo.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Só volta para telas da própria central, nunca para outro site. */
    static String destino(String volta) {
        return volta != null && VOLTA.matcher(volta).matches() ? volta : "/admin";
    }

    private static ResponseEntity<byte[]> ir(String destino) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(destino)).build();
    }
}
