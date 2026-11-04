package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlTasyTest {

    @Test
    void converteOrientacaoHtmlDoTasyEmTextoDeWhatsApp() {
        String html = "<html tasy=\"html5\"><body><h3>1. CHEGADA</h3><ul><li>Chegar com 45&nbsp;minutos.</li></ul>"
                + "<h3>2. JEJUM</h3><p>Jejum de 3 horas &amp; sem café.</p></body></html>";

        assertThat(HtmlTasy.texto(html)).isEqualTo(
                "1. CHEGADA\n• Chegar com 45 minutos.\n\n2. JEJUM\nJejum de 3 horas & sem café.");
    }

    @Test
    void htmlVazioViraTextoVazio() {
        assertThat(HtmlTasy.texto(null)).isEmpty();
        assertThat(HtmlTasy.texto("<html><body></body></html>")).isEmpty();
    }
}
