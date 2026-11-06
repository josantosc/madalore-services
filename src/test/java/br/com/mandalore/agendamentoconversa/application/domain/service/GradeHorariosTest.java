package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;

class GradeHorariosTest {

    /** 10/11/2026 é uma terça-feira. */
    private static final LocalDate TERCA = LocalDate.of(2026, 11, 10);
    private static final Agenda.Grade MANHA = new Agenda.Grade(1L, 7, 3, 2, LocalTime.of(8, 0), LocalTime.of(10, 0), 30, true, "Sala 1", true);
    private static final Agenda.Grade TARDE_SO_RECEPCAO = new Agenda.Grade(2L, 7, 3, 2, LocalTime.of(14, 0), LocalTime.of(15, 0), 30, false, "", true);

    @Test
    void gradeViraHorariosDoTamanhoDaDuracaoSoNoDiaDaSemana() {
        List<Agenda.Horario> horarios = GradeHorarios.gerar(List.of(MANHA), TERCA.minusDays(1), TERCA.plusDays(2));

        assertThat(horarios).extracting(h -> h.inicio().toLocalTime().toString())
                .containsExactly("08:00", "08:30", "09:00", "09:30");
        assertThat(horarios).allMatch(h -> h.inicio().toLocalDate().equals(TERCA));
        assertThat(horarios.get(0).fim()).isEqualTo(TERCA.atTime(8, 30));
    }

    @Test
    void sobraMenorQueADuracaoNaoViraHorario() {
        Agenda.Grade quebrada = new Agenda.Grade(3L, 7, 3, 2, LocalTime.of(8, 0), LocalTime.of(9, 10), 30, true, "", true);

        assertThat(GradeHorarios.gerar(List.of(quebrada), TERCA, TERCA.plusDays(1))).hasSize(2);
    }

    @Test
    void gradeInativaNaoGeraHorario() {
        Agenda.Grade inativa = new Agenda.Grade(4L, 7, 3, 2, LocalTime.of(8, 0), LocalTime.of(9, 0), 30, true, "", false);

        assertThat(GradeHorarios.gerar(List.of(inativa), TERCA, TERCA.plusDays(1))).isEmpty();
    }

    @Test
    void botSoOfereceVagaDoBotLivreDepoisDaAntecedenciaENoPeriodo() {
        Agenda.Agendamento ocupado = agendamento(TERCA.atTime(8, 30), Agenda.Status.MARCADO);
        Agenda.Agendamento cancelado = agendamento(TERCA.atTime(9, 0), Agenda.Status.CANCELADO);
        Agenda.Bloqueio reuniao = new Agenda.Bloqueio(1L, 7L, TERCA.atTime(9, 30), TERCA.atTime(10, 0), "Reunião", "ana");

        List<Agenda.Horario> vagas = GradeHorarios.vagasBot(List.of(MANHA, TARDE_SO_RECEPCAO), List.of(ocupado, cancelado), List.of(reuniao),
                TERCA.atStartOfDay(), TERCA.plusDays(1).atStartOfDay(), TERCA.atTime(7, 0), "tanto_faz");

        assertThat(vagas).extracting(h -> h.inicio().toLocalTime().toString()).containsExactly("08:00", "09:00");
    }

    @Test
    void antecedenciaEPeriodoFiltramComoNoTasy() {
        List<Agenda.Grade> grades = List.of(MANHA, new Agenda.Grade(5L, 7, 3, 2, LocalTime.of(13, 0), LocalTime.of(14, 0), 30, true, "", true));
        LocalDateTime de = TERCA.atStartOfDay();
        LocalDateTime ate = TERCA.plusDays(1).atStartOfDay();

        assertThat(GradeHorarios.vagasBot(grades, List.of(), List.of(), de, ate, TERCA.atTime(9, 0), "tanto_faz"))
                .extracting(h -> h.inicio().toLocalTime().toString()).containsExactly("09:00", "09:30", "13:00", "13:30");
        assertThat(GradeHorarios.vagasBot(grades, List.of(), List.of(), de, ate, TERCA.atTime(7, 0), "tarde"))
                .extracting(h -> h.inicio().toLocalTime().toString()).containsExactly("13:00", "13:30");
        assertThat(GradeHorarios.vagasBot(grades, List.of(), List.of(), de, ate, TERCA.atTime(7, 0), "manha")).hasSize(4);
    }

    @Test
    void bloqueioSemProfissionalValeParaAClinicaInteira() {
        Agenda.Bloqueio feriado = new Agenda.Bloqueio(2L, null, TERCA.atStartOfDay(), TERCA.plusDays(1).atStartOfDay(), "Feriado", "ana");

        assertThat(GradeHorarios.vagasBot(List.of(MANHA), List.of(), List.of(feriado), TERCA.atStartOfDay(),
                TERCA.plusDays(1).atStartOfDay(), TERCA.atTime(7, 0), "tanto_faz")).isEmpty();
    }

    @Test
    void visaoDoDiaMostraLivreOcupadoBloqueadoEEncaixe() {
        Agenda.Agendamento ocupado = agendamento(TERCA.atTime(8, 30), Agenda.Status.CONFIRMADO);
        Agenda.Agendamento encaixe = agendamento(TERCA.atTime(11, 15), Agenda.Status.MARCADO);
        Agenda.Bloqueio reuniao = new Agenda.Bloqueio(1L, 7L, TERCA.atTime(9, 30), TERCA.atTime(10, 0), "Reunião", "ana");

        List<Agenda.LinhaDia> linhas = VisaoAgenda.dia(TERCA, List.of(MANHA), List.of(ocupado, encaixe), List.of(reuniao),
                java.util.Map.of(7L, "Dra. Ana"), java.util.Map.of(3L, "Cardiologia"));

        assertThat(linhas).extracting(Agenda.LinhaDia::situacao).containsExactly(
                Agenda.SituacaoHorario.LIVRE, Agenda.SituacaoHorario.OCUPADO, Agenda.SituacaoHorario.LIVRE,
                Agenda.SituacaoHorario.BLOQUEADO, Agenda.SituacaoHorario.OCUPADO);
        assertThat(linhas.get(4).gradeId()).isNull();
        assertThat(linhas.get(0).profissional()).isEqualTo("Dra. Ana");
    }

    static Agenda.Agendamento agendamento(LocalDateTime inicio, Agenda.Status status) {
        return new Agenda.Agendamento(inicio.getMinute() + inicio.getHour() * 100L, 7, "Dra. Ana", 3, "Cardiologia", null, null,
                new Agenda.Paciente(1L, "52998224725", LocalDate.of(1988, 10, 19), "Maria", ""), inicio, inicio.plusMinutes(30), "",
                status, Agenda.Origem.BOT, null, null, null, List.of(), null);
    }
}
