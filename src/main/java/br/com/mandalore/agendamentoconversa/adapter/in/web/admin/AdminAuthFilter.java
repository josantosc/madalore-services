package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Login único da central (/admin) e das telas /curadoria, /precos e /simulador: sessão aberta em /admin/login
 * ou, para scripts, Basic Auth. Cada tela exige a permissão do seu {@link Modulo}.
 * O usuário autenticado fica no atributo {@link #USUARIO} da requisição.
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String USUARIO = "admin.usuario";
    /** Login completo ({@link AdminAcesso.Login}): a agenda confere por ele as clínicas que o usuário enxerga. */
    public static final String LOGIN = "admin.login.atual";
    public static final String SESSAO = "admin.login";
    static final String[] CAMINHOS = {
            "/admin", "/admin/*", "/curadoria", "/curadoria/*", "/precos", "/precos/*", "/simulador", "/simulador/*",
            "/agenda", "/agenda/*", "/funil", "/funil/*", "/usuarios", "/usuarios/*"};
    /** Com senha provisória, só estes caminhos abrem até a troca. */
    static final Set<String> LIVRES_NA_TROCA = Set.of("/admin/senha", "/admin/api/senha", "/admin/api/eu");

    private static final Logger LOG = LoggerFactory.getLogger(AdminAuthFilter.class);

    private final AdminAcesso acesso;

    public AdminAuthFilter(AdminAcesso acesso) {
        this.acesso = acesso;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String caminho = request.getRequestURI().substring(request.getContextPath().length());
        if (caminho.startsWith("/admin/assets/")) {
            chain.doFilter(request, response);
            return;
        }
        if (!acesso.configurado()) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Defina ADMIN_USER e ADMIN_PASSWORD");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if (caminho.equals("/admin/login") || caminho.equals("/admin/logout")) {
            chain.doFilter(request, response);
            return;
        }

        Optional<AdminAcesso.Login> login = daSessao(request);
        if (login.isPresent()) {
            try {
                if (!acesso.vigente(login.get())) {
                    LOG.info("admin sessao encerrada usuario={}: acesso alterado ou desativado", login.get().usuario());
                    request.getSession().invalidate();
                    login = Optional.empty();
                }
            } catch (DataAccessException ex) {
                LOG.warn("admin sessao sem banco: {}", ex.getMessage());
                json(response, HttpStatus.SERVICE_UNAVAILABLE, "Banco de dados indisponível. Tente de novo em instantes.");
                return;
            }
        }
        String authorization = request.getHeader("Authorization");
        if (login.isEmpty() && authorization != null) {
            String ip = AdminAcesso.ip(request);
            login = acesso.bloqueado(ip) ? Optional.empty() : acesso.autenticarBasic(authorization);
            if (login.isEmpty()) {
                acesso.registrarFalha(ip);
                LOG.warn("admin acesso recusado ip={}", ip);
                json(response, HttpStatus.UNAUTHORIZED, "Usuário ou senha incorretos");
                return;
            }
            acesso.limpar(ip);
        }
        if (login.isEmpty()) {
            if (paginaHtml(request, caminho)) {
                String volta = request.getQueryString() == null ? caminho : caminho + "?" + request.getQueryString();
                response.sendRedirect(request.getContextPath() + "/admin/login?volta="
                        + URLEncoder.encode(volta, StandardCharsets.UTF_8));
            } else {
                json(response, HttpStatus.UNAUTHORIZED, "Sessão expirada. Entre novamente.");
            }
            return;
        }

        if (login.get().trocarSenha() && !LIVRES_NA_TROCA.contains(caminho)) {
            if (paginaHtml(request, caminho)) {
                response.sendRedirect(request.getContextPath() + "/admin/senha");
            } else {
                json(response, HttpStatus.FORBIDDEN, "Troque a senha provisória antes de continuar");
            }
            return;
        }
        Optional<Modulo> modulo = Modulo.doCaminho(caminho);
        if (modulo.isPresent() && !login.get().modulos().contains(modulo.get())) {
            if (paginaHtml(request, caminho)) {
                response.sendRedirect(request.getContextPath() + "/admin");
            } else {
                json(response, HttpStatus.FORBIDDEN, "Seu usuário não tem acesso a esta tela");
            }
            return;
        }
        request.setAttribute(USUARIO, login.get().usuario());
        request.setAttribute(LOGIN, login.get());
        chain.doFilter(request, response);
    }

    private static Optional<AdminAcesso.Login> daSessao(HttpServletRequest request) {
        HttpSession sessao = request.getSession(false);
        return sessao != null && sessao.getAttribute(SESSAO) instanceof AdminAcesso.Login login
                ? Optional.of(login) : Optional.empty();
    }

    private static boolean paginaHtml(HttpServletRequest request, String caminho) {
        String aceita = request.getHeader("Accept");
        return "GET".equals(request.getMethod())
                && (Modulo.doCaminho(caminho).map(m -> m.caminho().equals(caminho)).orElse(caminho.equals("/admin"))
                || (aceita != null && aceita.contains(MediaType.TEXT_HTML_VALUE)));
    }

    private static void json(HttpServletResponse response, HttpStatus status, String mensagem) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"erro\":\"" + mensagem + "\"}");
    }
}
