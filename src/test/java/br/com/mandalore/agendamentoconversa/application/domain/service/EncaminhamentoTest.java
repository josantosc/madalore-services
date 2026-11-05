package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;

class EncaminhamentoTest {

    @Test
    void reconheceFaturamento() {
        assertThat(Encaminhamento.assunto("Preciso da AUTORIZAÇÃO do convênio")).contains(Setor.FATURAMENTO);
        assertThat(Encaminhamento.assunto("como pago o boleto?")).contains(Setor.FATURAMENTO);
    }

    @Test
    void reconheceLaboratorio() {
        assertThat(Encaminhamento.assunto("Meu resultado já saiu?")).contains(Setor.LABORATORIO);
        assertThat(Encaminhamento.assunto("precisa de jejum pra coleta?")).contains(Setor.LABORATORIO);
    }

    @Test
    void faturamentoVenceQuandoHaAmbos() {
        assertThat(Encaminhamento.assunto("o convênio cobre o resultado?")).contains(Setor.FATURAMENTO);
    }

    @Test
    void semAssunto() {
        assertThat(Encaminhamento.assunto("quero falar com um atendente")).isEmpty();
        assertThat(Encaminhamento.assunto(null)).isEmpty();
    }
}
