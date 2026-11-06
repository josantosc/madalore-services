package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Logins da central de administração. ADMIN_USER entra em tudo; CURADORIA_USER, PRECOS_USER e AGENDA_USER só no
 * próprio módulo; a recepção de cada clínica só na agenda da própria conta.
 * Senhas erradas bloqueiam o IP por {@link #BLOQUEIO}.
 */
@Component
public class AdminAcesso {

    static final int MAX_FALHAS = 5;
    static final Duration BLOQUEIO = Duration.ofMinutes(15);
    private static final int MAX_IPS = 10_000;

    /** contas: contas do Chatwoot (clínicas) que o login enxerga na agenda; vazio = todas. */
    public record Login(String usuario, Set<Modulo> modulos, Set<String> contas) implements Serializable {

        public Login {
            contas = contas == null ? Set.of() : Set.copyOf(contas);
        }

        public Login(String usuario, Set<Modulo> modulos) {
            this(usuario, modulos, Set.of());
        }

        public boolean podeVer(String conta) {
            return contas.isEmpty() || contas.contains(conta);
        }
    }

    record Credencial(String usuario, String senha, Set<Modulo> modulos, Set<String> contas) {

        Credencial(String usuario, String senha, Set<Modulo> modulos) {
            this(usuario, senha, modulos, Set.of());
        }

        boolean preenchida() {
            return !usuario.isBlank() && !senha.isBlank();
        }
    }

    private record Falhas(int total, Instant desde) {
    }

    private final List<Credencial> credenciais;
    private final Map<Modulo, Boolean> ativos;
    private final Clock relogio;
    private final Map<String, Falhas> falhas = new ConcurrentHashMap<>();

    @Autowired
    public AdminAcesso(Environment env) {
        this(credenciais(env), ativos(env), Clock.systemUTC());
    }

    AdminAcesso(List<Credencial> credenciais, Map<Modulo, Boolean> ativos, Clock relogio) {
        this.credenciais = credenciais.stream().filter(Credencial::preenchida).toList();
        this.ativos = ativos;
        this.relogio = relogio;
    }

    public boolean configurado() {
        return !credenciais.isEmpty();
    }

    public boolean ativo(Modulo modulo) {
        return ativos.getOrDefault(modulo, false);
    }

    /** Permissões de quem informou usuário e senha certos (vazio quando não confere). */
    public Optional<Login> autenticar(String usuario, String senha) {
        if (usuario == null || senha == null) {
            return Optional.empty();
        }
        String nome = usuario.trim();
        Set<Modulo> modulos = EnumSet.noneOf(Modulo.class);
        Set<String> contas = new HashSet<>();
        boolean todasContas = false;
        for (Credencial credencial : credenciais) {
            if (igual(credencial.usuario(), nome) & igual(credencial.senha(), senha)) {
                modulos.addAll(credencial.modulos());
                todasContas |= credencial.contas().isEmpty();
                contas.addAll(credencial.contas());
            }
        }
        return modulos.isEmpty() ? Optional.empty()
                : Optional.of(new Login(nome, Set.copyOf(modulos), todasContas ? Set.of() : contas));
    }

    /** Login de um cabeçalho Basic (scripts, como o curl da importação). */
    public Optional<Login> autenticarBasic(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            int separador = decoded.indexOf(':');
            return separador < 0 ? Optional.empty()
                    : autenticar(decoded.substring(0, separador), decoded.substring(separador + 1));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
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

    /** Recepção de cada clínica (mandalore.clientes.&lt;id&gt;.recepcao.*): só a agenda da própria conta. */
    private static List<Credencial> credenciais(Environment env) {
        List<Credencial> todas = new ArrayList<>(List.of(
                credencial(env, "admin", EnumSet.allOf(Modulo.class)),
                credencial(env, "curadoria", EnumSet.of(Modulo.CURADORIA)),
                credencial(env, "precos", EnumSet.of(Modulo.PRECOS)),
                credencial(env, "agenda", EnumSet.of(Modulo.AGENDA))));
        Binder.get(env)
                .bind("mandalore.clientes", Bindable.mapOf(String.class, ClientesProperties.Cliente.class))
                .orElse(Map.of())
                .forEach((conta, cliente) -> {
                    if (cliente.recepcao() != null && cliente.recepcao().configurada()) {
                        todas.add(new Credencial(cliente.recepcao().usuario().trim(), cliente.recepcao().senha(),
                                EnumSet.of(Modulo.AGENDA), Set.of(conta)));
                    }
                });
        return todas;
    }

    private static Credencial credencial(Environment env, String prefixo, Set<Modulo> modulos) {
        return new Credencial(
                env.getProperty(prefixo + ".user", "").trim(),
                env.getProperty(prefixo + ".password", ""),
                modulos);
    }

    private static Map<Modulo, Boolean> ativos(Environment env) {
        boolean postgres = env.getProperty("observability.postgres.enabled", Boolean.class, false);
        Map<Modulo, Boolean> ativos = new EnumMap<>(Modulo.class);
        ativos.put(Modulo.CURADORIA, postgres && env.getProperty("curadoria.enabled", Boolean.class, false));
        ativos.put(Modulo.PRECOS, postgres && env.getProperty("orcamento.enabled", Boolean.class, false));
        ativos.put(Modulo.SIMULADOR, env.getProperty("simulador.enabled", Boolean.class, false));
        ativos.put(Modulo.AGENDA, postgres && env.getProperty("agenda.enabled", Boolean.class, false));
        return ativos;
    }

    private static boolean igual(String esperado, String informado) {
        return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8), informado.getBytes(StandardCharsets.UTF_8));
    }
}
