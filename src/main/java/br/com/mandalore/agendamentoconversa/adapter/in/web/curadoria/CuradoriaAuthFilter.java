package br.com.mandalore.agendamentoconversa.adapter.in.web.curadoria;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Basic Auth das telas internas (curadoria, tabela de preços). Sem usuário e senha configurados, nada é liberado.
 * O usuário autenticado fica no atributo {@link #USUARIO} da requisição.
 */
public class CuradoriaAuthFilter extends OncePerRequestFilter {

    public static final String USUARIO = "curadoria.usuario";

    private static final Logger LOG = LoggerFactory.getLogger(CuradoriaAuthFilter.class);

    private final String usuario;
    private final String senha;
    private final String area;
    private final String variaveis;

    public CuradoriaAuthFilter(String usuario, String senha) {
        this(usuario, senha, "Curadoria", "CURADORIA_USER e CURADORIA_PASSWORD");
    }

    public CuradoriaAuthFilter(String usuario, String senha, String area, String variaveis) {
        this.usuario = usuario == null ? "" : usuario;
        this.senha = senha == null ? "" : senha;
        this.area = area;
        this.variaveis = variaveis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (usuario.isBlank() || senha.isBlank()) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Defina " + variaveis);
            return;
        }
        String informado = usuarioValido(request.getHeader("Authorization"));
        if (informado == null) {
            if (request.getHeader("Authorization") != null) {
                LOG.warn("{} acesso recusado ip={}", area, request.getRemoteAddr());
            }
            response.setHeader("WWW-Authenticate", "Basic realm=\"" + area + "\", charset=\"UTF-8\"");
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        request.setAttribute(USUARIO, informado);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private String usuarioValido(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            int separador = decoded.indexOf(':');
            if (separador < 0) {
                return null;
            }
            String nome = decoded.substring(0, separador);
            boolean ok = igual(usuario, nome) & igual(senha, decoded.substring(separador + 1));
            return ok ? nome : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean igual(String esperado, String informado) {
        return MessageDigest.isEqual(
                esperado.getBytes(StandardCharsets.UTF_8),
                informado.getBytes(StandardCharsets.UTF_8));
    }
}
