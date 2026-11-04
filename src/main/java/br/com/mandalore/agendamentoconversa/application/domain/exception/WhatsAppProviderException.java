package br.com.mandalore.agendamentoconversa.application.domain.exception;

public class WhatsAppProviderException extends RuntimeException {

    private final String providerCode;
    private final Integer httpStatus;

    public WhatsAppProviderException(
            String providerCode,
            Integer httpStatus,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.providerCode = providerCode;
        this.httpStatus = httpStatus;
    }

    public String providerCode() {
        return providerCode;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
