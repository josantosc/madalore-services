package br.com.mandalore.agendamentoconversa.adapter.in.web.simulador;

import java.util.List;
import java.util.Map;

import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;

public record SimuladorResponse(
        String texto,
        String intent,
        String acao,
        boolean transbordo,
        Map<String, Object> parametros,
        List<RespostaDialogflow.ContextoAtivo> contextos,
        String erroWebhook,
        long latenciaMs
) {

    public static SimuladorResponse de(RespostaDialogflow resposta, long latenciaMs) {
        String intent = resposta.intent() == null ? "" : resposta.intent();
        return new SimuladorResponse(
                resposta.texto(),
                intent,
                resposta.acao(),
                intent.startsWith("transbordo."),
                resposta.parametros(),
                resposta.contextos(),
                resposta.erroWebhook(),
                latenciaMs
        );
    }
}
