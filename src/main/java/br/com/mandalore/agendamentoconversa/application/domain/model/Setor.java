package br.com.mandalore.agendamentoconversa.application.domain.model;

public enum Setor {
    RECEPCAO,
    LABORATORIO,
    FATURAMENTO,
    /** Quem lança no sistema da clínica as pendências da agenda do Mandalore. Sem equipe própria, vai para a Recepção. */
    AGENDAMENTO
}
