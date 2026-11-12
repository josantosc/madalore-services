package br.com.mandalore.agendamentoconversa.application.domain.exception;

/** Fluxo clínico desligado ou conta sem a agenda do Mandalore. Os controllers traduzem para HTTP 404. */
public class AtendimentoDesabilitadoException extends RuntimeException {

    public AtendimentoDesabilitadoException() {
        super("Atendimento clínico não habilitado para esta clínica");
    }
}
