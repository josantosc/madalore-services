package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;
import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario.Perfil;
import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario.TipoEvento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.UsuarioUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.UsuarioPort;

@Service
@ConditionalOnProperty(name = "observability.postgres.enabled", havingValue = "true")
public class UsuarioService implements UsuarioUseCase {

    static final int MIN_SENHA = 10;
    static final Duration CACHE_SESSAO = Duration.ofSeconds(15);

    private static final Logger LOG = LoggerFactory.getLogger(UsuarioService.class);
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern CONTA = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final Set<String> COMUNS = Set.of("1234567890", "12345678910", "0123456789", "1111111111",
            "senha12345", "senha@1234", "senha123456", "password123", "qwertyuiop", "abcdefghij", "administrador",
            "mandalore123", "biosaude123", "bio@saude123");
    private static final int LIMITE_EVENTOS = 200;

    private record Estado(Optional<Integer> versao, Instant em) {
    }

    private final UsuarioPort usuarios;
    private final PasswordEncoder senhas;
    private final Clock relogio;
    private final SecureRandom aleatorio = new SecureRandom();
    private final String hashFalso;
    private final Map<Long, Estado> sessoes = new ConcurrentHashMap<>();

    @Autowired
    public UsuarioService(UsuarioPort usuarios) {
        this(usuarios, new BCryptPasswordEncoder(), Clock.systemUTC());
    }

    UsuarioService(UsuarioPort usuarios, PasswordEncoder senhas, Clock relogio) {
        this.usuarios = usuarios;
        this.senhas = senhas;
        this.relogio = relogio;
        this.hashFalso = senhas.encode("sem-usuario-" + aleatorio.nextLong());
    }

    @Override
    public Optional<Usuario.Cadastro> autenticar(String email, String senha) {
        if (email == null || senha == null || email.isBlank()) {
            return Optional.empty();
        }
        Optional<Usuario.ComSenha> encontrado = usuarios.porEmail(email.trim());
        if (encontrado.isEmpty() || !encontrado.get().cadastro().ativo()) {
            senhas.matches(senha, hashFalso);
            return Optional.empty();
        }
        return senhas.matches(senha, encontrado.get().senhaHash())
                ? Optional.of(encontrado.get().cadastro()) : Optional.empty();
    }

    @Override
    public boolean vigente(long id, int versao) {
        Instant agora = relogio.instant();
        Estado estado = sessoes.get(id);
        if (estado == null || estado.em().plus(CACHE_SESSAO).isBefore(agora)) {
            estado = new Estado(usuarios.versaoSeAtivo(id), agora);
            sessoes.put(id, estado);
        }
        return estado.versao().map(atual -> atual == versao).orElse(false);
    }

    @Override
    public void registrarLogin(long id, String email, String ip) {
        usuarios.acessou(id);
        usuarios.evento(TipoEvento.LOGIN, id, email, email, ip);
    }

    @Override
    public Optional<Usuario.Cadastro> buscar(long id) {
        return usuarios.porId(id).map(Usuario.ComSenha::cadastro);
    }

    @Override
    public void registrarLoginRecusado(String informado, String ip) {
        String alvo = informado == null ? "" : informado.trim();
        usuarios.evento(TipoEvento.LOGIN_RECUSADO, null, alvo.length() > 200 ? alvo.substring(0, 200) : alvo, "-", ip);
    }

    @Override
    public List<Usuario.Cadastro> listar(Usuario.Ator ator) {
        return usuarios.listar().stream().filter(u -> enxerga(ator, u)).toList();
    }

    @Override
    public String criar(Usuario.Ator ator, Usuario.Dados dados, String ip) {
        Usuario.Dados validos = validar(ator, dados);
        String provisoria = provisoria();
        long id = usuarios.criar(validos, senhas.encode(provisoria), ator.usuario());
        usuarios.evento(TipoEvento.USUARIO_CRIADO, id, validos.email(), ator.usuario(), ip);
        LOG.info("usuario criado id={} perfil={} autor={}", id, validos.perfil(), ator.usuario());
        return provisoria;
    }

    @Override
    public void alterar(Usuario.Ator ator, long id, Usuario.Dados dados, String ip) {
        Usuario.Cadastro atual = alvo(ator, id);
        Usuario.Dados validos = validar(ator, dados);
        if (ator.id() != null && ator.id() == id && (validos.perfil() != atual.perfil()
                || !validos.contas().equals(atual.contas()) || !validos.telasExtras().equals(atual.telasExtras())
                || !validos.telasRetiradas().equals(atual.telasRetiradas()))) {
            throw new IllegalArgumentException("Peça a outro administrador para mudar o seu próprio acesso");
        }
        usuarios.alterar(id, validos);
        sessoes.remove(id);
        usuarios.evento(TipoEvento.USUARIO_ALTERADO, id, validos.email(), ator.usuario(), ip);
    }

    @Override
    public void ativar(Usuario.Ator ator, long id, boolean ativo, String ip) {
        Usuario.Cadastro atual = alvo(ator, id);
        if (!ativo && ator.id() != null && ator.id() == id) {
            throw new IllegalArgumentException("Você não pode desativar o próprio usuário");
        }
        if (atual.ativo() == ativo) {
            return;
        }
        usuarios.ativo(id, ativo);
        sessoes.remove(id);
        usuarios.evento(ativo ? TipoEvento.USUARIO_REATIVADO : TipoEvento.USUARIO_DESATIVADO, id, atual.email(),
                ator.usuario(), ip);
    }

    @Override
    public String resetarSenha(Usuario.Ator ator, long id, String ip) {
        Usuario.Cadastro atual = alvo(ator, id);
        String provisoria = provisoria();
        usuarios.senha(id, senhas.encode(provisoria), true);
        sessoes.remove(id);
        usuarios.evento(TipoEvento.SENHA_RESETADA, id, atual.email(), ator.usuario(), ip);
        return provisoria;
    }

    @Override
    public Usuario.Cadastro trocarSenha(long id, String atual, String nova, String ip) {
        Usuario.ComSenha usuario = usuarios.porId(id).filter(u -> u.cadastro().ativo())
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        if (atual == null || !senhas.matches(atual, usuario.senhaHash())) {
            throw new IllegalArgumentException("Senha atual incorreta");
        }
        if (atual.equals(nova)) {
            throw new IllegalArgumentException("A nova senha precisa ser diferente da atual");
        }
        validarSenha(nova, usuario.cadastro().email());
        usuarios.senha(id, senhas.encode(nova), false);
        sessoes.remove(id);
        usuarios.evento(TipoEvento.SENHA_TROCADA, id, usuario.cadastro().email(), usuario.cadastro().email(), ip);
        return usuarios.porId(id).orElseThrow().cadastro();
    }

    @Override
    public List<Usuario.Evento> eventos(Usuario.Ator ator) {
        return usuarios.eventos(ator.geral() ? Set.of() : ator.contas(), LIMITE_EVENTOS);
    }

    @Override
    public Set<String> contasCadastradas() {
        Set<String> contas = new TreeSet<>();
        usuarios.listar().forEach(u -> contas.addAll(u.contas()));
        return contas;
    }

    static void validarSenha(String senha, String email) {
        if (senha == null || senha.length() < MIN_SENHA) {
            throw new IllegalArgumentException("A senha precisa ter pelo menos " + MIN_SENHA + " caracteres");
        }
        if (senha.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("A senha pode ter no máximo 72 caracteres");
        }
        String minuscula = senha.toLowerCase(Locale.ROOT);
        if (COMUNS.contains(minuscula) || minuscula.chars().distinct().count() < 4) {
            throw new IllegalArgumentException("Essa senha é muito fácil de adivinhar. Escolha outra");
        }
        String nome = email == null ? "" : email.toLowerCase(Locale.ROOT).split("@", 2)[0];
        if (nome.length() >= 3 && minuscula.contains(nome)) {
            throw new IllegalArgumentException("A senha não pode conter o seu e-mail");
        }
    }

    private String provisoria() {
        StringBuilder senha = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            senha.append(ALFABETO.charAt(aleatorio.nextInt(ALFABETO.length())));
        }
        return senha.toString();
    }

    private Usuario.Cadastro alvo(Usuario.Ator ator, long id) {
        return usuarios.porId(id).map(Usuario.ComSenha::cadastro).filter(u -> enxerga(ator, u))
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    /** O gestor da clínica só enxerga quem é exclusivamente das clínicas dele. */
    private static boolean enxerga(Usuario.Ator ator, Usuario.Cadastro usuario) {
        return ator.geral() || usuario.perfil() != Perfil.ADMINISTRADOR
                && !usuario.contas().isEmpty() && ator.contas().containsAll(usuario.contas());
    }

    private static Usuario.Dados validar(Usuario.Ator ator, Usuario.Dados dados) {
        String nome = dados.nome() == null ? "" : dados.nome().trim();
        String email = dados.email() == null ? "" : dados.email().trim().toLowerCase(Locale.ROOT);
        if (nome.isEmpty() || nome.length() > 200) {
            throw new IllegalArgumentException("Informe o nome");
        }
        if (email.length() > 200 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Informe um e-mail válido");
        }
        if (dados.perfil() == null) {
            throw new IllegalArgumentException("Escolha o perfil");
        }
        Set<String> telas = new HashSet<>(dados.telasExtras());
        telas.addAll(dados.telasRetiradas());
        if (!Usuario.TELAS.containsAll(telas)) {
            throw new IllegalArgumentException("Tela desconhecida");
        }
        Set<String> contas = Set.copyOf(dados.contas().stream().map(String::trim).filter(c -> !c.isEmpty()).toList());
        if (dados.perfil() == Perfil.ADMINISTRADOR) {
            if (!ator.geral()) {
                throw new IllegalArgumentException("Só o administrador geral cria outro administrador geral");
            }
            contas = Set.of();
        } else if (contas.isEmpty()) {
            throw new IllegalArgumentException("Escolha pelo menos uma clínica");
        } else if (!contas.stream().allMatch(c -> CONTA.matcher(c).matches())) {
            throw new IllegalArgumentException("Clínica inválida");
        }
        if (!ator.geral()) {
            if (!ator.contas().containsAll(contas)) {
                throw new IllegalArgumentException("Você só pode dar acesso às suas clínicas");
            }
            if (!ator.telas().containsAll(dados.telasExtras())) {
                throw new IllegalArgumentException("Você só pode dar acesso às telas que você tem");
            }
        }
        return new Usuario.Dados(nome, email, dados.perfil(), contas, dados.telasExtras(), dados.telasRetiradas());
    }
}
