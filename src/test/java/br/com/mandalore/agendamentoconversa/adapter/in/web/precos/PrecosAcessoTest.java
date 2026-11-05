package br.com.mandalore.agendamentoconversa.adapter.in.web.precos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PrecosAcessoTest {

    private Instant agora = Instant.parse("2026-11-05T20:00:00Z");
    private final Clock relogio = new Clock() {
        @Override
        public ZoneOffset getZone() {
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
    };
    private final PrecosAcesso acesso = new PrecosAcesso("financeiro", "s3nha", relogio);

    @Test
    void confereUsuarioESenha() {
        assertThat(acesso.confere("financeiro", "s3nha")).isTrue();
        assertThat(acesso.confere(" financeiro ", "s3nha")).isTrue();
        assertThat(acesso.confere("financeiro", "S3NHA")).isFalse();
        assertThat(acesso.confere("outro", "s3nha")).isFalse();
        assertThat(acesso.confere(null, null)).isFalse();
    }

    @Test
    void semCredenciaisNadaEntra() {
        PrecosAcesso vazio = new PrecosAcesso("", "", relogio);

        assertThat(vazio.configurado()).isFalse();
        assertThat(vazio.confere("", "")).isFalse();
    }

    @Test
    void bloqueiaOIpDepoisDeCincoErrosPorQuinzeMinutos() {
        for (int i = 0; i < PrecosAcesso.MAX_FALHAS - 1; i++) {
            acesso.registrarFalha("1.1.1.1");
        }
        assertThat(acesso.bloqueado("1.1.1.1")).isFalse();

        acesso.registrarFalha("1.1.1.1");
        assertThat(acesso.bloqueado("1.1.1.1")).isTrue();
        assertThat(acesso.bloqueado("2.2.2.2")).isFalse();

        agora = agora.plus(PrecosAcesso.BLOQUEIO).plus(Duration.ofSeconds(1));
        assertThat(acesso.bloqueado("1.1.1.1")).isFalse();
    }

    @Test
    void acertoZeraAsFalhas() {
        acesso.registrarFalha("1.1.1.1");
        acesso.limpar("1.1.1.1");
        for (int i = 0; i < PrecosAcesso.MAX_FALHAS - 1; i++) {
            acesso.registrarFalha("1.1.1.1");
        }

        assertThat(acesso.bloqueado("1.1.1.1")).isFalse();
    }

    @Test
    void leBasicAuthParaScripts() {
        String valido = "Basic " + Base64.getEncoder().encodeToString("financeiro:s3nha".getBytes(StandardCharsets.UTF_8));
        String errado = "Basic " + Base64.getEncoder().encodeToString("financeiro:x".getBytes(StandardCharsets.UTF_8));

        assertThat(acesso.usuarioBasic(valido)).isEqualTo("financeiro");
        assertThat(acesso.usuarioBasic(errado)).isNull();
        assertThat(acesso.usuarioBasic("Basic !!!")).isNull();
        assertThat(acesso.usuarioBasic("Bearer x")).isNull();
    }

    @Test
    void ipEOUltimoDoProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        assertThat(PrecosAcesso.ip(request)).isEqualTo("10.0.0.5");

        request.addHeader("X-Forwarded-For", "6.6.6.6, 200.1.2.3");
        assertThat(PrecosAcesso.ip(request)).isEqualTo("200.1.2.3");
    }
}
