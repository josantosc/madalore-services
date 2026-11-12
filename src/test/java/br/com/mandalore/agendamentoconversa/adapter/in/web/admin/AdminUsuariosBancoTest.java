package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.UsuarioUseCase;

class AdminUsuariosBancoTest {

    private static final Usuario.Cadastro ANA = new Usuario.Cadastro(7, "Ana", "ana@clinica.com", Usuario.Perfil.RECEPCAO,
            true, false, Set.of("1"), Set.of(), Set.of(), 3, "admin", null, null);

    private final UsuarioUseCase usuarios = mock(UsuarioUseCase.class);
    private final AdminAcesso acesso = new AdminAcesso(
            List.of(new AdminAcesso.Credencial("admin", "a", EnumSet.allOf(Modulo.class))), Map.of(), Clock.systemUTC(),
            () -> usuarios);
    private final AdminAuthFilter filtro = new AdminAuthFilter(acesso);

    @Test
    void telasDoCadastroSaoOsModulosDaCentral() {
        assertThat(Usuario.TELAS).containsExactlyInAnyOrderElementsOf(Arrays.stream(Modulo.values()).map(Modulo::name).toList());
    }

    @Test
    void cadastrosDaAgendaSaoUmaTelaSeparadaDaAgenda() {
        assertThat(Modulo.doCaminho("/agenda")).contains(Modulo.AGENDA);
        assertThat(Modulo.doCaminho("/agenda/api/dia")).contains(Modulo.AGENDA);
        assertThat(Modulo.doCaminho("/agenda/cadastros")).contains(Modulo.AGENDA_CADASTROS);
        assertThat(Modulo.doCaminho("/agenda/cadastros/api/especialidade/3")).contains(Modulo.AGENDA_CADASTROS);
        assertThat(Modulo.doCaminho("/agendamentos")).isEmpty();

        Usuario.Cadastro cadastrador = new Usuario.Cadastro(8, "Bia", "bia@clinica.com", Usuario.Perfil.CADASTRO_AGENDA,
                true, false, Set.of("1"), Set.of(), Set.of(), 1, "admin", null, null);
        assertThat(AdminAcesso.Login.de(cadastrador).modulos()).containsExactly(Modulo.AGENDA_CADASTROS);
        assertThat(AdminAcesso.Login.de(ANA).modulos()).doesNotContain(Modulo.AGENDA_CADASTROS);
    }

    @Test
    void usuarioDoBancoEntraPeloEmailComAsTelasEClinicasDoPerfil() {
        when(usuarios.autenticar("ana@clinica.com", "segredo1234")).thenReturn(Optional.of(ANA));

        AdminAcesso.Login login = acesso.autenticar(" ana@clinica.com ", "segredo1234").orElseThrow();

        assertThat(login.usuario()).isEqualTo("ana@clinica.com");
        assertThat(login.modulos()).containsExactlyInAnyOrder(Modulo.AGENDA, Modulo.FUNIL, Modulo.ATENDIMENTO);
        assertThat(login.contas()).containsExactly("1");
        assertThat(login.id()).isEqualTo(7L);
        assertThat(login.versao()).isEqualTo(3);
        assertThat(login.geral()).isFalse();
        assertThat(login.podeVer("2")).isFalse();
    }

    @Test
    void variaveisVemAntesDoBancoEUsuarioSemArrobaNaoConsultaOBanco() {
        assertThat(acesso.autenticar("admin", "a")).hasValueSatisfying(l -> assertThat(l.geral()).isTrue());
        assertThat(acesso.autenticar("fulano", "x")).isEmpty();
        verify(usuarios, never()).autenticar(anyString(), anyString());
    }

    @Test
    void bancoForaDoArNaoDerrubaOLoginDasVariaveis() {
        when(usuarios.autenticar(anyString(), anyString())).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(acesso.autenticar("ana@clinica.com", "x")).isEmpty();
        assertThat(acesso.autenticar("admin", "a")).isPresent();
    }

    @Test
    void sessaoDeUsuarioDesativadoOuAlteradoCai() throws Exception {
        when(usuarios.vigente(7, 3)).thenReturn(false);
        MockHttpServletRequest request = logado("GET", "/funil/api/oportunidades", AdminAcesso.Login.de(ANA));
        MockHttpSession sessao = (MockHttpSession) request.getSession();
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = executar(request, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(sessao.isInvalid()).isTrue();
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void sessaoVigenteSegue() throws Exception {
        when(usuarios.vigente(7, 3)).thenReturn(true);
        MockFilterChain chain = new MockFilterChain();

        executar(logado("GET", "/funil/api/oportunidades", AdminAcesso.Login.de(ANA)), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void bancoForaDoArNaSessaoResponde503SemEncerrarASessao() throws Exception {
        when(usuarios.vigente(anyLong(), anyInt())).thenThrow(new QueryTimeoutException("timeout"));
        MockHttpServletRequest request = logado("GET", "/funil/api/oportunidades", AdminAcesso.Login.de(ANA));

        MockHttpServletResponse response = executar(request, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(((MockHttpSession) request.getSession()).isInvalid()).isFalse();
    }

    @Test
    void senhaProvisoriaSoLiberaATrocaDeSenha() throws Exception {
        when(usuarios.vigente(7, 3)).thenReturn(true);
        AdminAcesso.Login provisorio = AdminAcesso.Login.de(new Usuario.Cadastro(7, "Ana", "ana@clinica.com",
                Usuario.Perfil.RECEPCAO, true, true, Set.of("1"), Set.of(), Set.of(), 3, "admin", null, null));

        MockHttpServletResponse pagina = executar(logado("GET", "/funil", provisorio), new MockFilterChain());
        assertThat(pagina.getRedirectedUrl()).isEqualTo("/admin/senha");

        MockHttpServletResponse api = executar(logado("POST", "/funil/api/oportunidades/1/etapa", provisorio), new MockFilterChain());
        assertThat(api.getStatus()).isEqualTo(403);

        for (String caminho : List.of("/admin/senha", "/admin/api/senha", "/admin/api/eu")) {
            MockFilterChain chain = new MockFilterChain();
            executar(logado("GET", caminho, provisorio), chain);
            assertThat(chain.getRequest()).as(caminho).isNotNull();
        }
    }

    @Test
    void gestorNaoEntraNaCuradoriaMasEntraEmUsuarios() throws Exception {
        when(usuarios.vigente(8, 1)).thenReturn(true);
        AdminAcesso.Login gestor = AdminAcesso.Login.de(new Usuario.Cadastro(8, "Gil", "gil@clinica.com",
                Usuario.Perfil.GESTOR, true, false, Set.of("1"), Set.of(), Set.of(), 1, "admin", null, null));

        MockFilterChain chain = new MockFilterChain();
        executar(logado("GET", "/usuarios/api/usuarios", gestor), chain);
        assertThat(chain.getRequest()).isNotNull();

        assertThat(executar(logado("GET", "/curadoria/api/falas", gestor), new MockFilterChain()).getStatus()).isEqualTo(403);
        assertThat(gestor.ator().geral()).isFalse();
        assertThat(gestor.ator().telas()).contains("USUARIOS");
    }

    private AdminAcesso.Login login(Usuario.Perfil perfil, Set<String> extras, Set<String> retiradas) {
        when(usuarios.vigente(20, 1)).thenReturn(true);
        return AdminAcesso.Login.de(new Usuario.Cadastro(20, "Teste", "teste@clinica.com", perfil, true, false,
                Set.of("1"), extras, retiradas, 1, "admin", null, null));
    }

    private int status(String caminho, AdminAcesso.Login login) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = executar(logado("GET", caminho, login), chain);
        return chain.getRequest() != null ? 200 : response.getStatus();
    }

    @Test
    void recepcaoTem403NoConsultorioEAcessaOAtendimento() throws Exception {
        AdminAcesso.Login recepcao = login(Usuario.Perfil.RECEPCAO, Set.of(), Set.of());

        assertThat(status("/consultorio/api/fila", recepcao)).isEqualTo(403);
        assertThat(status("/atendimento/api/fila", recepcao)).isEqualTo(200);
    }

    @Test
    void medicoAcessaConsultorioEAtendimentoMasNaoAAgenda() throws Exception {
        AdminAcesso.Login medico = login(Usuario.Perfil.MEDICO, Set.of(), Set.of());

        assertThat(status("/consultorio/api/fila", medico)).isEqualTo(200);
        assertThat(status("/atendimento/api/fila", medico)).isEqualTo(200);
        assertThat(status("/agenda/api/dia", medico)).isEqualTo(403);
    }

    @Test
    void telasExtrasConcedemEtelasRetiradasRemovemOConsultorio() throws Exception {
        assertThat(status("/consultorio/api/fila", login(Usuario.Perfil.GESTOR, Set.of(), Set.of()))).isEqualTo(403);
        assertThat(status("/consultorio/api/fila", login(Usuario.Perfil.GESTOR, Set.of("CONSULTORIO"), Set.of())))
                .isEqualTo(200);
        assertThat(status("/consultorio/api/fila", login(Usuario.Perfil.MEDICO, Set.of(), Set.of("CONSULTORIO"))))
                .isEqualTo(403);
    }

    private MockHttpServletResponse executar(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest logado(String metodo, String caminho, AdminAcesso.Login login) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, caminho);
        request.getSession(true).setAttribute(AdminAuthFilter.SESSAO, login);
        return request;
    }
}
