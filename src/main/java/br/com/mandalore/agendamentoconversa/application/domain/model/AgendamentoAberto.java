package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.LocalDateTime;

public record AgendamentoAberto(
        String nrSequencia,
        LocalDateTime dataHora,
        String especialidade,
        String medico,
        String local,
        String situacao
) {
}
