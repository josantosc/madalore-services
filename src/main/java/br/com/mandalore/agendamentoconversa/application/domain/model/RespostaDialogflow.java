package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.util.List;
import java.util.Map;

public record RespostaDialogflow(
        String intent,
        String texto,
        String acao,
        Map<String, Object> parametros,
        List<ContextoAtivo> contextos,
        String erroWebhook
) {

    public RespostaDialogflow {
        parametros = parametros == null ? Map.of() : parametros;
        contextos = contextos == null ? List.of() : List.copyOf(contextos);
    }

    public RespostaDialogflow(String intent, String texto) {
        this(intent, texto, "", Map.of(), List.of(), null);
    }

    public record ContextoAtivo(String nome, int lifespan) {
    }
}
