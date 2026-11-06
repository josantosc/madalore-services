package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;

class TextoPendenciaTest {

    private static final Agenda.Paciente MARIA = new Agenda.Paciente(1L, "52998224725", LocalDate.of(1988, 10, 19), "Maria da Silva", "(91) 99999-0000");

    @Test
    void lancarTrazTudoQueARecepcaoDigitaNoSistemaDaClinica() {
        String texto = TextoPendencia.resumo(Agenda.TipoPendencia.LANCAR, agendamento(LocalDateTime.of(2026, 11, 10, 9, 0), null), null, "");

        assertThat(texto).startsWith("Lançar no sistema da clínica")
                .contains("Paciente: Maria da Silva", "CPF: 529.982.247-25", "Nascimento: 19/10/1988", "Telefone: (91) 99999-0000",
                        "Profissional: Dra. Ana", "Especialidade: Cardiologia", "Convênio: Unimed", "Data: ter. 10/11/2026 às 09:00",
                        "Origem: bot do WhatsApp")
                .doesNotContain("Código");
    }

    @Test
    void remarcarMostraDeParaEONumeroDoSistema() {
        Agenda.Agendamento anterior = agendamento(LocalDateTime.of(2026, 11, 10, 9, 0), "A-123");
        Agenda.Agendamento nova = agendamento(LocalDateTime.of(2026, 11, 12, 14, 30), null);

        String texto = TextoPendencia.resumo(Agenda.TipoPendencia.REMARCAR, nova, anterior, "");

        assertThat(texto).contains("De: ter. 10/11/2026 às 09:00", "Código no sistema da clínica: A-123", "Para: qui. 12/11/2026 às 14:30");
    }

    @Test
    void cancelarDeRemarcacaoNaoLancadaApontaParaOAgendamentoQueEstaNoSistema() {
        Agenda.Agendamento noSistema = agendamento(LocalDateTime.of(2026, 11, 10, 9, 0), "A-123");
        Agenda.Agendamento remarcado = agendamento(LocalDateTime.of(2026, 11, 12, 14, 30), null);

        String texto = TextoPendencia.resumo(Agenda.TipoPendencia.CANCELAR, remarcado, noSistema, "");

        assertThat(texto).contains("Data: ter. 10/11/2026 às 09:00", "Código no sistema da clínica: A-123").doesNotContain("12/11");
    }

    private static Agenda.Agendamento agendamento(LocalDateTime inicio, String codigo) {
        return new Agenda.Agendamento(10, 7, "Dra. Ana", 3, "Cardiologia", 5L, "Unimed", MARIA, inicio, inicio.plusMinutes(30), "",
                Agenda.Status.MARCADO, Agenda.Origem.BOT, codigo, "55", null, List.of(), null);
    }
}
