package br.com.mandalore.agendamentoconversa.application.domain.model;

public record MensagemEntrada(
        String event,
        String messageType,
        boolean privada,
        String content,
        String messageId,
        String accountId,
        String conversationId,
        String status,
        Canal canal
) {
    public MensagemEntrada {
        canal = canal == null ? Canal.OUTRO : canal;
    }

    public boolean aceita() {
        return "message_created".equals(event)
                && ("incoming".equalsIgnoreCase(messageType) || "0".equals(messageType))
                && !privada
                && accountId != null
                && conversationId != null;
    }
}
