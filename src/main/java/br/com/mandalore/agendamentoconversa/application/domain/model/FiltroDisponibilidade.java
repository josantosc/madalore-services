package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record FiltroDisponibilidade(
        String medicoId,
        String especialidadeId,
        LocalDateTime inicio,
        LocalDateTime fim,
        String periodo
) {
}
