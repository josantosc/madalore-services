package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;
import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario.Perfil;
import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario.TipoEvento;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.UsuarioPort;

class UsuarioServiceTest {

    private static final Usuario.Ator ADMIN = new Usuario.Ator("admin", null, true, Set.of(), Set.copyOf(Usuario.TELAS));
    private static final Usuario.Ator GESTOR_1 = new Usuario.Ator("gestor@clinica1.com", 99L, false, Set.of("1"),
            Set.of("AGENDA", "FUNIL", "PRECOS", "USUARIOS"));

    private final MemoriaPort port = new MemoriaPort();
    private final MutableClock relogio = new MutableClock();
    private final UsuarioService service = new UsuarioService(port, new BCryptPasswordEncoder(4), relogio);

    @Test
    void criaComSenhaProvisoriaQueAutenticaEExigeTroca() {
        String senha = service.criar(ADMIN, dados("Ana", "Ana@Clinica.com", Perfil.RECEPCAO, "1"), "1.1.1.1");

        assertThat(senha).hasSize(12).doesNotContain("0", "O", "l", "1", "I");
        Usuario.Cadastro ana = service.autenticar("ana@clinica.com", senha).orElseThrow();
        assertThat(ana.email()).isEqualTo("ana@clinica.com");
        assertThat(ana.trocarSenha()).isTrue();
        assertThat(ana.telas()).containsExactlyInAnyOrder("AGENDA", "FUNIL");
        assertThat(port.eventos).extracting(e -> e.tipo()).containsExactly(TipoEvento.USUARIO_CRIADO);
        assertThat(service.autenticar("ana@clinica.com", "errada12345")).isEmpty();
        assertThat(service.autenticar("outro@clinica.com", senha)).isEmpty();
    }

    @Test
    void inativoNaoEntraEPerdeASessao() {
        String senha = service.criar(ADMIN, dados("Ana", "ana@clinica.com", Perfil.RECEPCAO, "1"), null);
        Usuario.Cadastro ana = service.autenticar("ana@clinica.com", senha).orElseThrow();
        assertThat(service.vigente(ana.id(), ana.versao())).isTrue();

        service.ativar(ADMIN, ana.id(), false, null);

        assertThat(service.autenticar("ana@clinica.com", senha)).isEmpty();
        assertThat(service.vigente(ana.id(), ana.versao())).isFalse();
    }

    @Test
    void alteracaoFeitaEmOutraInstanciaValeDepoisDoCache() {
        String senha = service.criar(ADMIN, dados("Ana", "ana@clinica.com", Perfil.RECEPCAO, "1"), null);
        Usuario.Cadastro ana = service.autenticar("ana@clinica.com", senha).orElseThrow();
        assertThat(service.vigente(ana.id(), ana.versao())).isTrue();

        port.ativo(ana.id(), false);
        assertThat(service.vigente(ana.id(), ana.versao())).isTrue();
        relogio.avancar(UsuarioService.CACHE_SESSAO.plusSeconds(1));
        assertThat(service.vigente(ana.id(), ana.versao())).isFalse();
    }

    @Test
    void trocaDeSenhaValidaARegraELiberaOAcesso() {
        String provisoria = service.criar(ADMIN, dados("Ana", "ana.souza@clinica.com", Perfil.RECEPCAO, "1"), null);
        long id = port.porEmail("ana.souza@clinica.com").orElseThrow().cadastro().id();

        assertThatThrownBy(() -> service.trocarSenha(id, "errada", "uma frase boa", null)).hasMessage("Senha atual incorreta");
        assertThatThrownBy(() -> service.trocarSenha(id, provisoria, "curta", null)).hasMessageContaining("10 caracteres");
        assertThatThrownBy(() -> service.trocarSenha(id, provisoria, "1234567890", null)).hasMessageContaining("fácil");
        assertThatThrownBy(() -> service.trocarSenha(id, provisoria, "aaaaaaaaaaaa", null)).hasMessageContaining("fácil");
        assertThatThrownBy(() -> service.trocarSenha(id, provisoria, "x-ana.souza-2026", null)).hasMessageContaining("e-mail");
        assertThatThrownBy(() -> service.trocarSenha(id, provisoria, provisoria, null)).hasMessageContaining("diferente");

        Usuario.Cadastro atualizado = service.trocarSenha(id, provisoria, "café com pão às 7h", "2.2.2.2");

        assertThat(atualizado.trocarSenha()).isFalse();
        assertThat(service.autenticar("ana.souza@clinica.com", "café com pão às 7h")).isPresent();
        assertThat(service.autenticar("ana.souza@clinica.com", provisoria)).isEmpty();
        assertThat(port.eventos).extracting(e -> e.tipo()).contains(TipoEvento.SENHA_TROCADA);
    }

    @Test
    void resetarSenhaVoltaAExigirTrocaEDerrubaASessao() {
        service.criar(ADMIN, dados("Ana", "ana@clinica.com", Perfil.RECEPCAO, "1"), null);
        Usuario.Cadastro ana = port.porEmail("ana@clinica.com").orElseThrow().cadastro();

        String nova = service.resetarSenha(ADMIN, ana.id(), null);

        assertThat(service.autenticar("ana@clinica.com", nova)).hasValueSatisfying(u -> assertThat(u.trocarSenha()).isTrue());
        assertThat(service.vigente(ana.id(), ana.versao())).isFalse();
    }

    @Test
    void administradorGeralNaoTemClinicaEOsOutrosPerfisPrecisamDeUma() {
        service.criar(ADMIN, dados("Fornecedor", "dev@mandalore.com", Perfil.ADMINISTRADOR, "1"), null);
        assertThat(port.porEmail("dev@mandalore.com").orElseThrow().cadastro().contas()).isEmpty();

        assertThatThrownBy(() -> service.criar(ADMIN, dados("Ana", "ana@clinica.com", Perfil.RECEPCAO), null))
                .hasMessage("Escolha pelo menos uma clínica");
        assertThatThrownBy(() -> service.criar(ADMIN, dados("Ana", "sem-arroba", Perfil.RECEPCAO, "1"), null))
                .hasMessage("Informe um e-mail válido");
    }

    @Test
    void gestorSoMexeNaPropriaClinicaESemCriarAdministrador() {
        assertThatThrownBy(() -> service.criar(GESTOR_1, dados("X", "x@c.com", Perfil.ADMINISTRADOR), null))
                .hasMessageContaining("administrador geral");
        assertThatThrownBy(() -> service.criar(GESTOR_1, dados("X", "x@c.com", Perfil.RECEPCAO, "2"), null))
                .hasMessageContaining("suas clínicas");
        Usuario.Dados comCuradoria = new Usuario.Dados("X", "x@c.com", Perfil.RECEPCAO, Set.of("1"), Set.of("CURADORIA"), Set.of());
        assertThatThrownBy(() -> service.criar(GESTOR_1, comCuradoria, null)).hasMessageContaining("telas que você tem");

        service.criar(GESTOR_1, dados("Bia", "bia@c.com", Perfil.FATURAMENTO, "1"), null);
        service.criar(ADMIN, dados("Caio", "caio@c.com", Perfil.RECEPCAO, "2"), null);
        service.criar(ADMIN, dados("Duda", "duda@c.com", Perfil.RECEPCAO, "1", "2"), null);
        service.criar(ADMIN, dados("Dev", "dev@c.com", Perfil.ADMINISTRADOR), null);

        assertThat(service.listar(GESTOR_1)).extracting(Usuario.Cadastro::nome).containsExactly("Bia");
        assertThat(service.listar(ADMIN)).hasSize(4);
        long caio = port.porEmail("caio@c.com").orElseThrow().cadastro().id();
        assertThatThrownBy(() -> service.ativar(GESTOR_1, caio, false, null)).hasMessage("Usuário não encontrado");
        assertThatThrownBy(() -> service.resetarSenha(GESTOR_1, caio, null)).hasMessage("Usuário não encontrado");
    }

    @Test
    void ninguemDesativaOuMudaOProprioAcesso() {
        service.criar(ADMIN, dados("Gestora", "g@c.com", Perfil.GESTOR, "1"), null);
        Usuario.Cadastro gestora = port.porEmail("g@c.com").orElseThrow().cadastro();
        Usuario.Ator ela = new Usuario.Ator("g@c.com", gestora.id(), false, Set.of("1"), gestora.telas());

        assertThatThrownBy(() -> service.ativar(ela, gestora.id(), false, null)).hasMessageContaining("próprio usuário");
        assertThatThrownBy(() -> service.alterar(ela, gestora.id(), dados("Gestora", "g@c.com", Perfil.RECEPCAO, "1"), null))
                .hasMessageContaining("próprio acesso");

        service.alterar(ela, gestora.id(), dados("Gestora Silva", "g@c.com", Perfil.GESTOR, "1"), null);
        assertThat(port.porId(gestora.id()).orElseThrow().cadastro().nome()).isEqualTo("Gestora Silva");
    }

    @Test
    void telasExtrasERetiradasAjustamOPerfil() {
        Usuario.Dados dados = new Usuario.Dados("Ana", "ana@c.com", Perfil.RECEPCAO, Set.of("1"), Set.of("PRECOS"), Set.of("FUNIL"));
        service.criar(ADMIN, dados, null);

        assertThat(port.porEmail("ana@c.com").orElseThrow().cadastro().telas()).containsExactlyInAnyOrder("AGENDA", "PRECOS");
        assertThatThrownBy(() -> service.criar(ADMIN,
                new Usuario.Dados("B", "b@c.com", Perfil.RECEPCAO, Set.of("1"), Set.of("INEXISTENTE"), Set.of()), null))
                .hasMessage("Tela desconhecida");
    }

    @Test
    void eventosDoGestorFicamNaClinicaDele() {
        service.eventos(GESTOR_1);
        assertThat(port.contasDosEventos).isEqualTo(Set.of("1"));
        service.eventos(ADMIN);
        assertThat(port.contasDosEventos).isEmpty();
    }

    private static Usuario.Dados dados(String nome, String email, Perfil perfil, String... contas) {
        return new Usuario.Dados(nome, email, perfil, Set.of(contas), Set.of(), Set.of());
    }

    private static final class MutableClock extends Clock {
        private Instant agora = Instant.parse("2026-11-06T12:00:00Z");

        void avancar(Duration tempo) {
            agora = agora.plus(tempo);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    private static final class MemoriaPort implements UsuarioPort {
        private final Map<Long, Usuario.ComSenha> usuarios = new LinkedHashMap<>();
        private final List<Usuario.Evento> eventos = new ArrayList<>();
        private Set<String> contasDosEventos;
        private long sequencia;

        @Override
        public Optional<Usuario.ComSenha> porEmail(String email) {
            return usuarios.values().stream().filter(u -> u.cadastro().email().equalsIgnoreCase(email)).findFirst();
        }

        @Override
        public Optional<Usuario.ComSenha> porId(long id) {
            return Optional.ofNullable(usuarios.get(id));
        }

        @Override
        public List<Usuario.Cadastro> listar() {
            return usuarios.values().stream().map(Usuario.ComSenha::cadastro).toList();
        }

        @Override
        public long criar(Usuario.Dados dados, String senhaHash, String autor) {
            if (porEmail(dados.email()).isPresent()) {
                throw new IllegalArgumentException("Já existe um usuário com esse e-mail");
            }
            long id = ++sequencia;
            usuarios.put(id, new Usuario.ComSenha(new Usuario.Cadastro(id, dados.nome(), dados.email(), dados.perfil(), true,
                    true, dados.contas(), dados.telasExtras(), dados.telasRetiradas(), 1, autor, Instant.now(), null), senhaHash));
            return id;
        }

        @Override
        public void alterar(long id, Usuario.Dados d) {
            Usuario.ComSenha atual = usuarios.get(id);
            Usuario.Cadastro c = atual.cadastro();
            usuarios.put(id, new Usuario.ComSenha(new Usuario.Cadastro(id, d.nome(), d.email(), d.perfil(), c.ativo(),
                    c.trocarSenha(), d.contas(), d.telasExtras(), d.telasRetiradas(), c.versao() + 1, c.criadoPor(),
                    c.criadoEm(), c.ultimoAcessoEm()), atual.senhaHash()));
        }

        @Override
        public void ativo(long id, boolean ativo) {
            Usuario.ComSenha atual = usuarios.get(id);
            Usuario.Cadastro c = atual.cadastro();
            usuarios.put(id, new Usuario.ComSenha(new Usuario.Cadastro(id, c.nome(), c.email(), c.perfil(), ativo,
                    c.trocarSenha(), c.contas(), c.telasExtras(), c.telasRetiradas(), c.versao() + 1, c.criadoPor(),
                    c.criadoEm(), c.ultimoAcessoEm()), atual.senhaHash()));
        }

        @Override
        public void senha(long id, String senhaHash, boolean trocarSenha) {
            Usuario.Cadastro c = usuarios.get(id).cadastro();
            usuarios.put(id, new Usuario.ComSenha(new Usuario.Cadastro(id, c.nome(), c.email(), c.perfil(), c.ativo(),
                    trocarSenha, c.contas(), c.telasExtras(), c.telasRetiradas(), c.versao() + 1, c.criadoPor(),
                    c.criadoEm(), c.ultimoAcessoEm()), senhaHash));
        }

        @Override
        public void acessou(long id) {
        }

        @Override
        public Optional<Integer> versaoSeAtivo(long id) {
            return porId(id).map(Usuario.ComSenha::cadastro).filter(Usuario.Cadastro::ativo).map(Usuario.Cadastro::versao);
        }

        @Override
        public void evento(TipoEvento tipo, Long usuarioId, String alvo, String autor, String ip) {
            eventos.add(new Usuario.Evento(Instant.now(), tipo, alvo, autor, ip));
        }

        @Override
        public List<Usuario.Evento> eventos(Set<String> contas, int limite) {
            contasDosEventos = contas;
            return eventos;
        }
    }
}
