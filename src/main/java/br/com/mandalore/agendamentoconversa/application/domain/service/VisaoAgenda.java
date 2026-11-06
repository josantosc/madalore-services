package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;

/** Visão do dia e da semana na tela da recepção: horários da grade, consultas, encaixes e bloqueios juntos. */
public final class VisaoAgenda {

    private VisaoAgenda() {
    }

    /**
     * Linhas do dia: cada horário da grade (livre, ocupado ou bloqueado) e as consultas fora da grade (encaixes).
     * Consulta que cobre mais de um horário aparece uma vez só.
     */
    public static List<Agenda.LinhaDia> dia(
            LocalDate dia,
            List<Agenda.Grade> grades,
            List<Agenda.Agendamento> ativos,
            List<Agenda.Bloqueio> bloqueios,
            Map<Long, String> profissionais,
            Map<Long, String> especialidades
    ) {
        List<Agenda.LinhaDia> linhas = new ArrayList<>();
        Set<Long> mostrados = new HashSet<>();
        for (Agenda.Horario horario : GradeHorarios.gerar(grades, dia, dia.plusDays(1))) {
            Agenda.Agendamento ocupante = GradeHorarios.ocupante(horario, ativos);
            if (ocupante != null) {
                if (mostrados.add(ocupante.id())) {
                    linhas.add(linha(ocupante, horario.gradeId(), horario.ofereceBot()));
                }
                continue;
            }
            Agenda.Bloqueio bloqueio = GradeHorarios.bloqueio(horario, bloqueios);
            linhas.add(new Agenda.LinhaDia(horario.inicio(), horario.fim(), horario.profissionalId(),
                    profissionais.get(horario.profissionalId()), horario.especialidadeId(),
                    especialidades.get(horario.especialidadeId()), horario.local(), horario.gradeId(), horario.ofereceBot(),
                    bloqueio == null ? Agenda.SituacaoHorario.LIVRE : Agenda.SituacaoHorario.BLOQUEADO, null, bloqueio));
        }
        ativos.stream()
                .filter(a -> a.inicio().toLocalDate().equals(dia) && !mostrados.contains(a.id()))
                .forEach(a -> linhas.add(linha(a, null, false)));
        linhas.sort(Comparator.comparing(Agenda.LinhaDia::inicio)
                .thenComparing(l -> l.profissional() == null ? "" : l.profissional()));
        return linhas;
    }

    /** Segunda a domingo da semana do dia: horários livres, livres para o bot (a partir de aPartirDe) e ocupados. */
    public static List<Agenda.ResumoDia> semana(
            LocalDate dia,
            List<Agenda.Grade> grades,
            List<Agenda.Agendamento> ativos,
            List<Agenda.Bloqueio> bloqueios,
            LocalDateTime aPartirDe
    ) {
        LocalDate segunda = inicioSemana(dia);
        List<Agenda.ResumoDia> semana = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate atual = segunda.plusDays(i);
            long livres = 0;
            long livresBot = 0;
            for (Agenda.Horario horario : GradeHorarios.gerar(grades, atual, atual.plusDays(1))) {
                if (GradeHorarios.livre(horario, ativos, bloqueios)) {
                    livres++;
                    if (horario.ofereceBot() && !horario.inicio().isBefore(aPartirDe)) {
                        livresBot++;
                    }
                }
            }
            long ocupados = ativos.stream().filter(a -> a.inicio().toLocalDate().equals(atual)).count();
            semana.add(new Agenda.ResumoDia(atual, livres, livresBot, ocupados));
        }
        return semana;
    }

    public static LocalDate inicioSemana(LocalDate dia) {
        return dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static Agenda.LinhaDia linha(Agenda.Agendamento agendamento, Long gradeId, boolean ofereceBot) {
        return new Agenda.LinhaDia(agendamento.inicio(), agendamento.fim(), agendamento.profissionalId(),
                agendamento.profissional(), agendamento.especialidadeId(), agendamento.especialidade(), agendamento.local(),
                gradeId, ofereceBot, Agenda.SituacaoHorario.OCUPADO, agendamento, null);
    }
}
