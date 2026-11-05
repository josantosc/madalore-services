package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.util.List;
import java.util.Optional;

public record MensagemEntrada(
        String event,
        String messageType,
        boolean privada,
        String content,
        String messageId,
        String accountId,
        String conversationId,
        String status,
        Canal canal,
        List<Anexo> anexos
) {
    public MensagemEntrada {
        canal = canal == null ? Canal.OUTRO : canal;
        anexos = anexos == null ? List.of() : List.copyOf(anexos);
    }

    public MensagemEntrada(
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
        this(event, messageType, privada, content, messageId, accountId, conversationId, status, canal, List.of());
    }

    public boolean aceita() {
        return "message_created".equals(event)
                && ("incoming".equalsIgnoreCase(messageType) || "0".equals(messageType))
                && !privada
                && accountId != null
                && conversationId != null;
    }

    public Optional<Anexo> documento() {
        return anexos.stream().filter(Anexo::documento).findFirst();
    }
}
