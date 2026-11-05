package br.com.mandalore.agendamentoconversa.application.domain.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MascaraTest {

    @Test
    void cpfComOuSemPontuacao() {
        assertThat(Mascara.texto("meu cpf é 529.982.247-25")).isEqualTo("meu cpf é <cpf>");
        assertThat(Mascara.texto("52998224725")).isEqualTo("<cpf>");
    }

    @Test
    void datas() {
        assertThat(Mascara.texto("nasci em 20/05/1990")).isEqualTo("nasci em <data>");
        assertThat(Mascara.texto("pode ser dia 12-11?")).isEqualTo("pode ser dia <data>?");
    }

    @Test
    void telefoneEmailENumeroLongo() {
        assertThat(Mascara.texto("liga (86) 99533-2198")).isEqualTo("liga <telefone>");
        assertThat(Mascara.texto("ana.silva+1@gmail.com")).isEqualTo("<email>");
        assertThat(Mascara.texto("carteirinha 123456789")).isEqualTo("carteirinha <numero>");
    }

    @Test
    void mantemOpcoesDeMenuEHorarios() {
        assertThat(Mascara.texto("opção 2")).isEqualTo("opção 2");
        assertThat(Mascara.texto("quero às 14h de 2026")).isEqualTo("quero às 14h de 2026");
    }

    @Test
    void textoVazio() {
        assertThat(Mascara.texto(null)).isEmpty();
        assertThat(Mascara.texto("  ")).isEmpty();
    }
}
