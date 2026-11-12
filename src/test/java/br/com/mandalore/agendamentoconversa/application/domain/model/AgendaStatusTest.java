package br.com.mandalore.agendamentoconversa.application.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgendaStatusTest {

    @Test
    void temOsSeteStatusNaOrdemDoCiclo() {
        assertThat(Agenda.Status.values()).containsExactly(
                Agenda.Status.MARCADO, Agenda.Status.CONFIRMADO, Agenda.Status.CANCELADO, Agenda.Status.REMARCADO,
                Agenda.Status.AGUARDANDO, Agenda.Status.EM_CONSULTA, Agenda.Status.EXECUTADO);
    }

    @Test
    void ocupaHorarioDaMarcacaoAteAConsultaExecutada() {
        assertThat(Agenda.Status.values()).filteredOn(Agenda.Status::ocupaHorario).containsExactly(
                Agenda.Status.MARCADO, Agenda.Status.CONFIRMADO, Agenda.Status.AGUARDANDO,
                Agenda.Status.EM_CONSULTA, Agenda.Status.EXECUTADO);
    }

    @Test
    void ativoSoParaMarcadoEConfirmado() {
        assertThat(Agenda.Status.values()).filteredOn(Agenda.Status::ativo)
                .containsExactly(Agenda.Status.MARCADO, Agenda.Status.CONFIRMADO);
    }
}
