package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.util.Map;

public record FulfillmentCommand(
        String session,
        String intent,
        String queryText,
        Map<String, Object> parameters,
        ContextoConversa contexto
) {
    public String operacao() {
        if (intent == null) {
            return "";
        }
        int ponto = intent.lastIndexOf('.');
        return ponto < 0 ? intent : intent.substring(ponto + 1);
    }

    public boolean acao() {
        return intent != null && intent.startsWith("acao.");
    }
}
