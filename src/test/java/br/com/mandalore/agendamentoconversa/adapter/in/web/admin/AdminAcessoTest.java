package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminAcessoTest {

    private static final AdminAcesso.Credencial ADMIN = new AdminAcesso.Credencial("admin", "a", EnumSet.allOf(Modulo.class));
    private static final AdminAcesso.Credencial FINANCEIRO = new AdminAcesso.Credencial("fin", "f", EnumSet.of(Modulo.PRECOS));

    static String basic(String usuario, String senha) {
        return "Basic " + Base64.getEncoder().encodeToString((usuario + ":" + senha).getBytes(StandardCharsets.UTF_8));
    }

    private static AdminAcesso acesso(Clock relogio, AdminAcesso.Credencial... credenciais) {
        return new AdminAcesso(List.of(credenciais), Map.of(Modulo.PRECOS, true), relogio);
    }

    @Test
    void cadaLoginRecebeSoOsModulosDaSuaCredencial() {
        AdminAcesso acesso = acesso(Clock.systemUTC(), ADMIN, FINANCEIRO);

        assertThat(acesso.autenticar("admin", "a")).get()
                .extracting(AdminAcesso.Login::modulos).isEqualTo(EnumSet.allOf(Modulo.class));
        assertThat(acesso.autenticar(" fin ", "f")).get()
                .satisfies(login -> {
                    assertThat(login.usuario()).isEqualTo("fin");
                    assertThat(login.modulos()).containsExactly(Modulo.PRECOS);
                });
        assertThat(acesso.autenticar("fin", "a")).isEmpty();
        assertThat(acesso.autenticar(null, "a")).isEmpty();
    }

    @Test
    void mesmoUsuarioEmDuasCredenciaisSomaAsPermissoes() {
        AdminAcesso acesso = acesso(Clock.systemUTC(),
                new AdminAcesso.Credencial("ana", "x", EnumSet.of(Modulo.CURADORIA)),
                new AdminAcesso.Credencial("ana", "x", EnumSet.of(Modulo.PRECOS)));

        assertThat(acesso.autenticar("ana", "x")).get()
                .extracting(AdminAcesso.Login::modulos).isEqualTo(EnumSet.of(Modulo.CURADORIA, Modulo.PRECOS));
    }

    @Test
    void credencialIncompletaNaoConta() {
        AdminAcesso acesso = acesso(Clock.systemUTC(), new AdminAcesso.Credencial("admin", "", EnumSet.allOf(Modulo.class)));

        assertThat(acesso.configurado()).isFalse();
        assertThat(acesso.autenticar("admin", "")).isEmpty();
    }

    @Test
    void lePropriedadesEModulosLigados() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("precos.user", "fin").withProperty("precos.password", "f")
                .withProperty("observability.postgres.enabled", "true")
                .withProperty("orcamento.enabled", "true")
                .withProperty("curadoria.enabled", "true");
        AdminAcesso acesso = new AdminAcesso(env);

        assertThat(acesso.configurado()).isTrue();
        assertThat(acesso.autenticar("fin", "f")).isPresent();
        assertThat(acesso.ativo(Modulo.PRECOS)).isTrue();
        assertThat(acesso.ativo(Modulo.CURADORIA)).isTrue();
        assertThat(acesso.ativo(Modulo.SIMULADOR)).isFalse();
    }

    @Test
    void recepcaoDeCadaClinicaSoEnxergaAAgendaDaPropriaConta() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("admin.user", "admin").withProperty("admin.password", "a")
                .withProperty("mandalore.clientes.3.recepcao.usuario", "rec3").withProperty("mandalore.clientes.3.recepcao.senha", "s3")
                .withProperty("mandalore.clientes.4.recepcao.usuario", "rec4")
                .withProperty("observability.postgres.enabled", "true")
                .withProperty("agenda.enabled", "true");
        AdminAcesso acesso = new AdminAcesso(env);

        AdminAcesso.Login recepcao = acesso.autenticar("rec3", "s3").orElseThrow();
        assertThat(recepcao.modulos()).containsExactly(Modulo.AGENDA);
        assertThat(recepcao.podeVer("3")).isTrue();
        assertThat(recepcao.podeVer("4")).isFalse();
        assertThat(acesso.autenticar("rec4", "")).as("sem senha não vira login").isEmpty();
        AdminAcesso.Login admin = acesso.autenticar("admin", "a").orElseThrow();
        assertThat(admin.contas()).isEmpty();
        assertThat(admin.podeVer("4")).isTrue();
        assertThat(acesso.ativo(Modulo.AGENDA)).isTrue();
    }

    @Test
    void basicAuthAceitaSoCabecalhoValido() {
        AdminAcesso acesso = acesso(Clock.systemUTC(), FINANCEIRO);

        assertThat(acesso.autenticarBasic(basic("fin", "f"))).isPresent();
        assertThat(acesso.autenticarBasic("basic " + basic("fin", "f").substring(6))).isPresent();
        assertThat(acesso.autenticarBasic(basic("fin", "x"))).isEmpty();
        assertThat(acesso.autenticarBasic("Basic !!!")).isEmpty();
        assertThat(acesso.autenticarBasic("Bearer abc")).isEmpty();
        assertThat(acesso.autenticarBasic(null)).isEmpty();
    }

    @Test
    void bloqueiaOIpDepoisDeCincoFalhasAteOPrazoAcabar() {
        RelogioAjustavel relogio = new RelogioAjustavel();
        AdminAcesso acesso = acesso(relogio, FINANCEIRO);

        for (int i = 0; i < AdminAcesso.MAX_FALHAS; i++) {
            assertThat(acesso.bloqueado("1.1.1.1")).isFalse();
            acesso.registrarFalha("1.1.1.1");
        }
        assertThat(acesso.bloqueado("1.1.1.1")).isTrue();
        assertThat(acesso.bloqueado("2.2.2.2")).isFalse();

        relogio.avancar(AdminAcesso.BLOQUEIO.plusSeconds(1));
        assertThat(acesso.bloqueado("1.1.1.1")).isFalse();

        acesso.registrarFalha("3.3.3.3");
        acesso.limpar("3.3.3.3");
        assertThat(acesso.bloqueado("3.3.3.3")).isFalse();
    }

    @Test
    void ipVemDoUltimoSaltoDoProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        assertThat(AdminAcesso.ip(request)).isEqualTo("10.0.0.1");

        request.addHeader("X-Forwarded-For", "6.6.6.6, 200.1.1.1");
        assertThat(AdminAcesso.ip(request)).isEqualTo("200.1.1.1");
    }

    @Test
    void voltaSoParaTelasDaCentral() {
        assertThat(AdminController.destino("/precos")).isEqualTo("/precos");
        assertThat(AdminController.destino("/agenda?pendencia=7")).isEqualTo("/agenda?pendencia=7");
        assertThat(AdminController.destino("/curadoria?conversa=12")).isEqualTo("/curadoria?conversa=12");
        assertThat(AdminController.destino("//evil.com")).isEqualTo("/admin");
        assertThat(AdminController.destino("https://evil.com/precos")).isEqualTo("/admin");
        assertThat(AdminController.destino("/precosx")).isEqualTo("/admin");
        assertThat(AdminController.destino(null)).isEqualTo("/admin");
    }

    private static final class RelogioAjustavel extends Clock {
        private Instant agora = Instant.parse("2026-01-01T10:00:00Z");

        void avancar(Duration tempo) {
            agora = agora.plus(tempo);
        }

        @Override
        public Instant instant() {
            return agora;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
