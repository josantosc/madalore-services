package br.com.mandalore.agendamentoconversa.adapter.in.web.precos;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Protege /precos: sessão aberta pela tela de login ou, para scripts (curl), Basic Auth.
 * O usuário autenticado fica no atributo {@link #USUARIO} da requisição.
 */
public class PrecosAuthFilter extends OncePerRequestFilter {

    public static final String USUARIO = "precos.usuario";
    static final String SESSAO = "precos.usuario";

    private static final Logger LOG = LoggerFactory.getLogger(PrecosAuthFilter.class);

    private final PrecosAcesso acesso;

    public PrecosAuthFilter(PrecosAcesso acesso) {
        this.acesso = acesso;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!acesso.configurado()) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Defina PRECOS_USER e PRECOS_PASSWORD");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        String caminho = request.getRequestURI().substring(request.getContextPath().length());
        if (caminho.equals("/precos/login") || caminho.equals("/precos/logout")) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession sessao = request.getSession(false);
        if (sessao != null && sessao.getAttribute(SESSAO) instanceof String usuario) {
            request.setAttribute(USUARIO, usuario);
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            String ip = PrecosAcesso.ip(request);
            String usuario = acesso.bloqueado(ip) ? null : acesso.usuarioBasic(authorization);
            if (usuario != null) {
                acesso.limpar(ip);
                request.setAttribute(USUARIO, usuario);
                chain.doFilter(request, response);
                return;
            }
            acesso.registrarFalha(ip);
            LOG.warn("tabela de precos acesso recusado ip={}", ip);
            negar(response, "Usuário ou senha incorretos");
            return;
        }
        String aceita = request.getHeader("Accept");
        if ("GET".equals(request.getMethod())
                && (caminho.equals("/precos") || (aceita != null && aceita.contains(MediaType.TEXT_HTML_VALUE)))) {
            response.sendRedirect(request.getContextPath() + "/precos/login");
            return;
        }
        negar(response, "Sessão expirada. Entre novamente.");
    }

    private static void negar(HttpServletResponse response, String mensagem) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"erro\":\"" + mensagem + "\"}");
    }
}
