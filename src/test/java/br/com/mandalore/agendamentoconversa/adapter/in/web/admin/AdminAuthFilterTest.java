package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import static br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAcessoTest.basic;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuthFilterTest {

    private final AdminAcesso acesso = new AdminAcesso(
            List.of(new AdminAcesso.Credencial("fin", "f", EnumSet.of(Modulo.PRECOS))), Map.of(), Clock.systemUTC());
    private final AdminAuthFilter filtro = new AdminAuthFilter(acesso);

    private MockHttpServletResponse executar(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest logado(String metodo, String caminho, Set<Modulo> modulos) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, caminho);
        request.getSession(true).setAttribute(AdminAuthFilter.SESSAO, new AdminAcesso.Login("fin", modulos));
        return request;
    }

    @Test
    void semCredencialConfiguradaNadaEhLiberado() throws Exception {
        AdminAuthFilter semLogin = new AdminAuthFilter(new AdminAcesso(List.of(), Map.of(), Clock.systemUTC()));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        semLogin.doFilter(new MockHttpServletRequest("GET", "/admin/login"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void paginaSemSessaoVaiParaOLoginEApiRecebe401() throws Exception {
        MockHttpServletRequest pagina = new MockHttpServletRequest("GET", "/curadoria");
        pagina.setQueryString("conversa=7");
        MockHttpServletResponse response = executar(pagina, new MockFilterChain());
        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/login?volta=%2Fcuradoria%3Fconversa%3D7");

        MockFilterChain chain = new MockFilterChain();
        response = executar(new MockHttpServletRequest("GET", "/precos/api/itens"), chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void loginEAssetsPassamSemSessao() throws Exception {
        for (String caminho : List.of("/admin/login", "/admin/assets/nav.js")) {
            MockFilterChain chain = new MockFilterChain();
            executar(new MockHttpServletRequest("GET", caminho), chain);
            assertThat(chain.getRequest()).as(caminho).isNotNull();
        }
    }

    @Test
    void painelDaTvPassaSemSessaoMasORestoDoAtendimentoNao() throws Exception {
        for (String caminho : List.of("/atendimento/painel", "/atendimento/painel/api/chamadas", "/atendimento/painel/api/eventos",
                "/atendimento/painel/api/checkin")) {
            MockFilterChain chain = new MockFilterChain();
            executar(new MockHttpServletRequest("GET", caminho), chain);
            assertThat(chain.getRequest()).as(caminho).isNotNull();
        }
        for (String caminho : List.of("/atendimento/api/fila", "/atendimento/painel/../api/fila", "/atendimento/painel/x")) {
            MockFilterChain chain = new MockFilterChain();
            executar(new MockHttpServletRequest("GET", caminho), chain);
            assertThat(chain.getRequest()).as(caminho).isNull();
        }
    }

    @Test
    void sessaoLiberaSoOsModulosDoUsuario() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest precos = logado("POST", "/precos/api/importar", EnumSet.of(Modulo.PRECOS));
        MockHttpServletResponse response = executar(precos, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(precos.getAttribute(AdminAuthFilter.USUARIO)).isEqualTo("fin");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");

        chain = new MockFilterChain();
        response = executar(logado("GET", "/simulador", EnumSet.of(Modulo.PRECOS)), chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/admin");
        assertThat(chain.getRequest()).isNull();

        response = executar(logado("POST", "/curadoria/api/ensinar", EnumSet.of(Modulo.PRECOS)), new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);

        chain = new MockFilterChain();
        executar(logado("GET", "/admin/api/eu", EnumSet.of(Modulo.PRECOS)), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void atendimentoEConsultorioSaoProtegidosEExigemOModuloProprio() throws Exception {
        assertThat(AdminAuthFilter.CAMINHOS).contains(
                "/atendimento", "/atendimento/*", "/consultorio", "/consultorio/*");

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = executar(new MockHttpServletRequest("GET", "/consultorio/api/fila"), chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();

        response = executar(logado("GET", "/consultorio/api/fila", EnumSet.of(Modulo.ATENDIMENTO)), new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        response = executar(logado("POST", "/atendimento/api/chegada", EnumSet.of(Modulo.CONSULTORIO)), new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        response = executar(logado("GET", "/consultorio", EnumSet.of(Modulo.ATENDIMENTO)), new MockFilterChain());
        assertThat(response.getRedirectedUrl()).isEqualTo("/admin");

        chain = new MockFilterChain();
        executar(logado("GET", "/consultorio/api/fila", EnumSet.of(Modulo.CONSULTORIO)), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void basicAuthServeParaScriptsEContaFalhas() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/precos/api/itens");
        ok.addHeader("Authorization", basic("fin", "f"));
        executar(ok, chain);
        assertThat(chain.getRequest()).isNotNull();

        for (int i = 0; i < AdminAcesso.MAX_FALHAS; i++) {
            MockHttpServletRequest errado = new MockHttpServletRequest("GET", "/precos/api/itens");
            errado.setRemoteAddr("9.9.9.9");
            errado.addHeader("Authorization", basic("fin", "x"));
            assertThat(executar(errado, new MockFilterChain()).getStatus()).isEqualTo(401);
        }
        assertThat(acesso.bloqueado("9.9.9.9")).isTrue();

        chain = new MockFilterChain();
        MockHttpServletRequest certoBloqueado = new MockHttpServletRequest("GET", "/precos/api/itens");
        certoBloqueado.setRemoteAddr("9.9.9.9");
        certoBloqueado.addHeader("Authorization", basic("fin", "f"));
        assertThat(executar(certoBloqueado, chain).getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
