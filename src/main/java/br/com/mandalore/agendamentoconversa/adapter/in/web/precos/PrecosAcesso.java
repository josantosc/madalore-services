package br.com.mandalore.agendamentoconversa.adapter.in.web.precos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/** Usuário e senha do financeiro (PRECOS_USER/PRECOS_PASSWORD) e bloqueio por IP após senhas erradas. */
@Component
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PrecosAcesso {

    static final int MAX_FALHAS = 5;
    static final Duration BLOQUEIO = Duration.ofMinutes(15);
    private static final int MAX_IPS = 10_000;

    private record Falhas(int total, Instant desde) {
    }

    private final String usuario;
    private final String senha;
    private final Clock relogio;
    private final Map<String, Falhas> falhas = new ConcurrentHashMap<>();

    @Autowired
    public PrecosAcesso(@Value("${precos.user:}") String usuario, @Value("${precos.password:}") String senha) {
        this(usuario, senha, Clock.systemUTC());
    }

    PrecosAcesso(String usuario, String senha, Clock relogio) {
        this.usuario = usuario == null ? "" : usuario.trim();
        this.senha = senha == null ? "" : senha;
        this.relogio = relogio;
    }

    public boolean configurado() {
        return !usuario.isBlank() && !senha.isBlank();
    }

    public boolean confere(String nome, String senhaInformada) {
        if (!configurado() || nome == null || senhaInformada == null) {
            return false;
        }
        return igual(usuario, nome.trim()) & igual(senha, senhaInformada);
    }

    /** Nome do usuário de um cabeçalho Basic válido, ou null. */
    public String usuarioBasic(String authorization) {
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
            return confere(nome, decoded.substring(separador + 1)) ? nome.trim() : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean bloqueado(String ip) {
        Falhas atual = falhas.get(ip);
        if (atual == null) {
            return false;
        }
        if (expirou(atual)) {
            falhas.remove(ip, atual);
            return false;
        }
        return atual.total() >= MAX_FALHAS;
    }

    public void registrarFalha(String ip) {
        if (falhas.size() >= MAX_IPS) {
            falhas.clear();
        }
        falhas.merge(ip, new Falhas(1, relogio.instant()),
                (atual, nova) -> expirou(atual) ? nova : new Falhas(atual.total() + 1, atual.desde()));
    }

    public void limpar(String ip) {
        falhas.remove(ip);
    }

    /** Atrás do proxy do Coolify o IP do cliente é o último do X-Forwarded-For (o que o proxy acrescentou). */
    public static String ip(HttpServletRequest request) {
        String encaminhado = request.getHeader("X-Forwarded-For");
        if (encaminhado != null && !encaminhado.isBlank()) {
            String[] ips = encaminhado.split(",");
            return ips[ips.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean expirou(Falhas falha) {
        return falha.desde().plus(BLOQUEIO).isBefore(relogio.instant());
    }

    private static boolean igual(String esperado, String informado) {
        return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8), informado.getBytes(StandardCharsets.UTF_8));
    }
}
