package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextosTest {

    @Test
    void sessaoCurtaCabeNoLimiteDoDetectIntent() {
        String sessao = Textos.sessionId("8", "9");

        assertThat(sessao).isEqualTo("cw-8-9");
        assertThat(sessao).hasSizeLessThanOrEqualTo(36);
        assertThat(sessao).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void sessaoLongaViraHashDe36Caracteres() {
        String sessao = Textos.sessionId("12345678901234567890", "98765432109876543210");

        assertThat(sessao).hasSize(36);
        assertThat(sessao).startsWith("cw");
        assertThat(sessao).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void textoLongoEhDivididoEntreParagrafosSemPassarDoLimite() {
        String paragrafo = "x".repeat(40);
        String texto = String.join("\n\n", paragrafo, paragrafo, paragrafo);

        assertThat(Textos.partes(texto, 90)).containsExactly(paragrafo + "\n\n" + paragrafo, paragrafo);
        assertThat(Textos.partes("curto", 90)).containsExactly("curto");
        assertThat(Textos.partes("y".repeat(25), 10)).allSatisfy(parte -> assertThat(parte).hasSizeLessThanOrEqualTo(10));
    }

    @Test
    void limiteEmBytesReduzOTamanhoQuandoHaAcento() {
        String texto = "ação ".repeat(400);

        assertThat(Textos.partes(texto, 900, 1000))
                .allSatisfy(parte -> assertThat(parte.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        .isLessThanOrEqualTo(1000));
    }

    @Test
    void contaVemDaSessaoDoDialogflow() {
        assertThat(Textos.accountId("projects/p/agent/sessions/cw-2-15")).isEqualTo("2");
        assertThat(Textos.accountId("cw-8-9")).isEqualTo("8");
        assertThat(Textos.accountId("sim-abc")).isNull();
        assertThat(Textos.accountId(null)).isNull();
    }
}
