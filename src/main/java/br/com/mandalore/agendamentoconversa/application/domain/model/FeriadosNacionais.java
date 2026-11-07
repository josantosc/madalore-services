package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Feriados nacionais (Lei 662/1949, Lei 6.802/1980 e Lei 14.759/2023) e a Sexta-feira Santa. Carnaval e Corpus
 * Christi são ponto facultativo: a clínica que fecha nesses dias cadastra como feriado local.
 */
public final class FeriadosNacionais {

    private static final Set<MonthDay> FIXOS = Set.of(
            MonthDay.of(1, 1), MonthDay.of(4, 21), MonthDay.of(5, 1), MonthDay.of(9, 7), MonthDay.of(10, 12),
            MonthDay.of(11, 2), MonthDay.of(11, 15), MonthDay.of(11, 20), MonthDay.of(12, 25));

    private FeriadosNacionais() {
    }

    public static boolean feriado(LocalDate dia) {
        return FIXOS.contains(MonthDay.from(dia)) || dia.equals(pascoa(dia.getYear()).minusDays(2));
    }

    /** Feriados de {@code de} até {@code ate}, inclusive, em ordem. */
    public static List<LocalDate> entre(LocalDate de, LocalDate ate) {
        List<LocalDate> dias = new ArrayList<>();
        for (LocalDate dia = de; !dia.isAfter(ate); dia = dia.plusDays(1)) {
            if (feriado(dia)) {
                dias.add(dia);
            }
        }
        return dias;
    }

    /** Domingo de Páscoa pelo algoritmo de Meeus/Jones/Butcher (calendário gregoriano). */
    static LocalDate pascoa(int ano) {
        int a = ano % 19;
        int b = ano / 100;
        int c = ano % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31;
        int dia = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(ano, mes, dia);
    }
}
