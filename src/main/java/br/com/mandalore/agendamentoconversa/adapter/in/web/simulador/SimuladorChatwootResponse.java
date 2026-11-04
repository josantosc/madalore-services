package br.com.mandalore.agendamentoconversa.adapter.in.web.simulador;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;

public record SimuladorChatwootResponse(
        String conversationId,
        String status,
        boolean emAtendimento,
        String painelUrl,
        List<ConversaVisitante.Mensagem> mensagens
) {

    public static SimuladorChatwootResponse de(ConversaVisitante conversa, String baseUrl, String accountId) {
        String painel = null;
        if (conversa.conversationId() != null && notBlank(baseUrl) && notBlank(accountId)) {
            painel = baseUrl.replaceAll("/$", "") + "/app/accounts/" + accountId + "/conversations/" + conversa.conversationId();
        }
        return new SimuladorChatwootResponse(
                conversa.conversationId(),
                conversa.status(),
                conversa.emAtendimento(),
                painel,
                conversa.mensagens()
        );
    }

    private static boolean notBlank(String valor) {
        return valor != null && !valor.isBlank();
    }
}
