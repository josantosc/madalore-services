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
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.UsuarioUseCase;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Logins da central de administração. Primeiro as variáveis de ambiente: ADMIN_USER entra em tudo (e é o acesso de
 * emergência, sem banco); CURADORIA_USER, PRECOS_USER, AGENDA_USER e FUNIL_USER só no próprio módulo; a recepção de
 * cada clínica só na agenda da própria conta. Depois os usuários cadastrados em /usuarios (login pelo e-mail).
 * Senhas erradas bloqueiam o IP por {@link #BLOQUEIO}.
 */
@Component
public class AdminAcesso {

    static final int MAX_FALHAS = 5;
    static final Duration BLOQUEIO = Duration.ofMinutes(15);
    private static final int MAX_IPS = 10_000;
    private static final Logger LOG = LoggerFactory.getLogger(AdminAcesso.class);

    /**
     * contas: contas do Chatwoot (clínicas) que o login enxerga; vazio = todas. id e versao: só dos usuários
     * cadastrados no banco (nulo nos logins das variáveis).
     */
    public record Login(String usuario, Set<Modulo> modulos, Set<String> contas, Long id, int versao,
                        boolean trocarSenha, String nome) implements Serializable {

        public Login {
            contas = contas == null ? Set.of() : Set.copyOf(contas);
        }

        public Login(String usuario, Set<Modulo> modulos, Set<String> contas) {
            this(usuario, modulos, contas, null, 0, false, null);
        }

        public Login(String usuario, Set<Modulo> modulos) {
            this(usuario, modulos, Set.of());
        }

        public boolean podeVer(String conta) {
            return contas.isEmpty() || contas.contains(conta);
        }

        public boolean doBanco() {
            return id != null;
        }

        public boolean geral() {
            return contas.isEmpty() && modulos.containsAll(EnumSet.allOf(Modulo.class));
        }

        public Usuario.Ator ator() {
            return new Usuario.Ator(usuario, id, geral(), contas,
                    modulos.stream().map(Modulo::name).collect(Collectors.toSet()));
        }

        public static Login de(Usuario.Cadastro usuario) {
            Set<Modulo> modulos = EnumSet.noneOf(Modulo.class);
            usuario.telas().forEach(tela -> modulos.add(Modulo.valueOf(tela)));
            return new Login(usuario.email(), Set.copyOf(modulos), usuario.contas(), usuario.id(), usuario.versao(),
                    usuario.trocarSenha(), usuario.nome());
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
    private final Supplier<UsuarioUseCase> usuarios;
    private final Map<String, Falhas> falhas = new ConcurrentHashMap<>();

    public AdminAcesso(Environment env) {
        this(credenciais(env), ativos(env), Clock.systemUTC());
    }

    @Autowired
    public AdminAcesso(Environment env, ObjectProvider<UsuarioUseCase> usuarios) {
        this(credenciais(env), ativos(env), Clock.systemUTC(), usuarios::getIfAvailable);
    }

    AdminAcesso(List<Credencial> credenciais, Map<Modulo, Boolean> ativos, Clock relogio) {
        this(credenciais, ativos, relogio, () -> null);
    }

    AdminAcesso(List<Credencial> credenciais, Map<Modulo, Boolean> ativos, Clock relogio, Supplier<UsuarioUseCase> usuarios) {
        this.credenciais = credenciais.stream().filter(Credencial::preenchida).toList();
        this.ativos = ativos;
        this.relogio = relogio;
        this.usuarios = usuarios;
    }

    public boolean configurado() {
        return !credenciais.isEmpty() || usuarios.get() != null;
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
        if (!modulos.isEmpty()) {
            return Optional.of(new Login(nome, Set.copyOf(modulos), todasContas ? Set.of() : contas));
        }
        UsuarioUseCase cadastro = usuarios.get();
        if (cadastro == null || !nome.contains("@")) {
            return Optional.empty();
        }
        try {
            return cadastro.autenticar(nome, senha).map(Login::de);
        } catch (DataAccessException ex) {
            LOG.warn("admin login sem banco: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A sessão de um usuário do banco ainda vale (ativo e sem alteração de acesso ou senha desde o login).
     * Lança {@link DataAccessException} com o banco fora do ar.
     */
    public boolean vigente(Login login) {
        UsuarioUseCase cadastro = usuarios.get();
        return !login.doBanco() || cadastro != null && cadastro.vigente(login.id(), login.versao());
    }

    public Optional<UsuarioUseCase> usuarios() {
        return Optional.ofNullable(usuarios.get());
    }

    public void registrarLogin(Login login, String ip) {
        auditar(cadastro -> {
            if (login.doBanco()) {
                cadastro.registrarLogin(login.id(), login.usuario(), ip);
            }
        });
    }

    public void registrarRecusado(String usuario, String ip) {
        auditar(cadastro -> cadastro.registrarLoginRecusado(usuario, ip));
    }

    private void auditar(Consumer<UsuarioUseCase> acao) {
        UsuarioUseCase cadastro = usuarios.get();
        if (cadastro == null) {
            return;
        }
        try {
            acao.accept(cadastro);
        } catch (DataAccessException ex) {
            LOG.warn("auditoria de acesso nao gravada: {}", ex.getMessage());
        }
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
                credencial(env, "agenda", EnumSet.of(Modulo.AGENDA, Modulo.AGENDA_CADASTROS)),
                credencial(env, "funil", EnumSet.of(Modulo.FUNIL))));
        Binder.get(env)
                .bind("mandalore.clientes", Bindable.mapOf(String.class, ClientesProperties.Cliente.class))
                .orElse(Map.of())
                .forEach((conta, cliente) -> {
                    if (cliente.recepcao() != null && cliente.recepcao().configurada()) {
                        todas.add(new Credencial(cliente.recepcao().usuario().trim(), cliente.recepcao().senha(),
                                EnumSet.of(Modulo.AGENDA, Modulo.AGENDA_CADASTROS), Set.of(conta)));
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
        ativos.put(Modulo.AGENDA_CADASTROS, ativos.get(Modulo.AGENDA));
        ativos.put(Modulo.FUNIL, postgres && env.getProperty("funil.enabled", Boolean.class, false));
        ativos.put(Modulo.USUARIOS, postgres);
        ativos.put(Modulo.ATENDIMENTO, postgres && env.getProperty("atendimento.enabled", Boolean.class, false));
        ativos.put(Modulo.CONSULTORIO, ativos.get(Modulo.ATENDIMENTO));
        ativos.put(Modulo.MEDICAMENTOS_ROTINA, ativos.get(Modulo.ATENDIMENTO));
        return ativos;
    }

    private static boolean igual(String esperado, String informado) {
        return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8), informado.getBytes(StandardCharsets.UTF_8));
    }
}
