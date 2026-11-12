package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;

/** Horários que a grade gera, sem tabela de horários pré-gerados. */
public final class GradeHorarios {

    private GradeHorarios() {
    }

    /** Todos os horários das grades ativas nos dias [de, ate), em ordem. */
    public static List<Agenda.Horario> gerar(List<Agenda.Grade> grades, LocalDate de, LocalDate ate) {
        List<Agenda.Horario> horarios = new ArrayList<>();
        for (LocalDate dia = de; dia.isBefore(ate); dia = dia.plusDays(1)) {
            for (Agenda.Grade grade : grades) {
                if (!grade.ativo() || !grade.valeEm(dia) || grade.id() == null) {
                    continue;
                }
                int fim = minutos(grade.fim());
                for (int minuto = minutos(grade.inicio()); minuto + grade.duracaoMin() <= fim; minuto += grade.duracaoMin()) {
                    LocalDateTime inicio = dia.atStartOfDay().plusMinutes(minuto);
                    horarios.add(new Agenda.Horario(inicio, inicio.plusMinutes(grade.duracaoMin()), grade.id(),
                            grade.profissionalId(), grade.especialidadeId(), grade.local(), grade.ofereceBot()));
                }
            }
        }
        horarios.sort(Comparator.comparing(Agenda.Horario::inicio).thenComparing(Agenda.Horario::profissionalId));
        return horarios;
    }

    public static boolean livre(Agenda.Horario horario, List<Agenda.Agendamento> ativos, List<Agenda.Bloqueio> bloqueios) {
        return ocupante(horario, ativos) == null && bloqueio(horario, bloqueios) == null;
    }

    public static Agenda.Agendamento ocupante(Agenda.Horario horario, List<Agenda.Agendamento> ativos) {
        return ativos.stream()
                .filter(a -> a.status().ocupaHorario() && a.profissionalId() == horario.profissionalId()
                        && a.inicio().isBefore(horario.fim()) && horario.inicio().isBefore(a.fim()))
                .findFirst()
                .orElse(null);
    }

    public static Agenda.Bloqueio bloqueio(Agenda.Horario horario, List<Agenda.Bloqueio> bloqueios) {
        return bloqueios.stream()
                .filter(b -> b.atinge(horario.profissionalId(), horario.inicio(), horario.fim()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Horários que o bot pode oferecer: vaga do bot, livres, a partir de {@code aPartirDe} e no período pedido
     * ("manha" antes do meio-dia, "tarde" a partir dele, como na agenda do Tasy).
     */
    public static List<Agenda.Horario> vagasBot(
            List<Agenda.Grade> grades,
            List<Agenda.Agendamento> ativos,
            List<Agenda.Bloqueio> bloqueios,
            LocalDateTime de,
            LocalDateTime ate,
            LocalDateTime aPartirDe,
            String periodo
    ) {
        LocalDateTime inicio = de.isBefore(aPartirDe) ? aPartirDe : de;
        return gerar(grades, inicio.toLocalDate(), ate.toLocalDate().plusDays(1)).stream()
                .filter(Agenda.Horario::ofereceBot)
                .filter(h -> !h.inicio().isBefore(inicio) && h.inicio().isBefore(ate))
                .filter(h -> noPeriodo(h.inicio(), periodo))
                .filter(h -> livre(h, ativos, bloqueios))
                .toList();
    }

    private static int minutos(LocalTime hora) {
        return hora.getHour() * 60 + hora.getMinute();
    }

    static boolean noPeriodo(LocalDateTime inicio, String periodo) {
        if ("manha".equals(periodo)) {
            return inicio.getHour() < 12;
        }
        if ("tarde".equals(periodo)) {
            return inicio.getHour() >= 12;
        }
        return true;
    }
}
