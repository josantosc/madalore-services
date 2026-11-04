package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.LocalDateTime;

public record HorarioOferta(
        String nrSequencia,
        String cdAgenda,
        LocalDateTime dataHora,
        String medico,
        String especialidade,
        String local
) {
}
