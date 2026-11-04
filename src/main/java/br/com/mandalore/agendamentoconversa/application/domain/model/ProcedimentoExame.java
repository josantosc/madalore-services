package br.com.mandalore.agendamentoconversa.application.domain.model;

public record ProcedimentoExame(
        String nrSeqProcInterno,
        String cdProcedimento,
        String ieOrigemProced,
        String cdIntegracao
) {
}
