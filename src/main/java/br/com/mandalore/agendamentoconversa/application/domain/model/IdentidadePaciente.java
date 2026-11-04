package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.LocalDate;

public record IdentidadePaciente(String cpf, LocalDate nascimento, String nomeCompleto) {
}
