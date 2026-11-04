package br.com.mandalore.agendamentoconversa.application.domain.exception;

public class ConsultaIndisponivelException extends RuntimeException {

    public ConsultaIndisponivelException(String message) {
        super(message);
    }

    public ConsultaIndisponivelException(String message, Throwable cause) {
        super(message, cause);
    }
}
