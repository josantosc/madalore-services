package br.com.mandalore.agendamentoconversa.adapter.in.web.curadoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CuradoriaAuthFilterTest {

    private static String basic(String usuario, String senha) {
        return "Basic " + Base64.getEncoder().encodeToString((usuario + ":" + senha).getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletResponse executar(CuradoriaAuthFilter filtro, MockHttpServletRequest request, MockFilterChain chain)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, chain);
        return response;
    }

    @Test
    void semCredencialConfiguradaNadaEhLiberado() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/curadoria");
        request.addHeader("Authorization", basic("", ""));

        MockHttpServletResponse response = executar(new CuradoriaAuthFilter("", ""), request, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void senhaErradaPedeLogin() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/curadoria/api/fila");
        request.addHeader("Authorization", basic("curadoria", "errada"));

        MockHttpServletResponse response = executar(new CuradoriaAuthFilter("curadoria", "segredo"), request, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Basic");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void credencialCertaSegueComOUsuario() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/curadoria");
        request.addHeader("Authorization", basic("curadoria", "segredo"));

        MockHttpServletResponse response = executar(new CuradoriaAuthFilter("curadoria", "segredo"), request, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(CuradoriaAuthFilter.USUARIO)).isEqualTo("curadoria");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
