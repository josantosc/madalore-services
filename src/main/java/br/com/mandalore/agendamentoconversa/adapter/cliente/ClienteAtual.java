package br.com.mandalore.agendamentoconversa.adapter.cliente;

import java.util.function.Supplier;

/**
 * Conta e conversa do Chatwoot da requisição em andamento. A conta escolhe o Oracle do Tasy e a agenda daquele cliente.
 * Vale só na thread da requisição: chamada assíncrona não herda o valor.
 */
public final class ClienteAtual {

    private static final ThreadLocal<String> ACCOUNT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private ClienteAtual() {
    }

    public static String accountId() {
        return ACCOUNT_ID.get();
    }

    public static String conversationId() {
        return CONVERSATION_ID.get();
    }

    public static <T> T executar(String accountId, Supplier<T> acao) {
        return executar(accountId, null, acao);
    }

    public static <T> T executar(String accountId, String conversationId, Supplier<T> acao) {
        String conta = ACCOUNT_ID.get();
        String conversa = CONVERSATION_ID.get();
        definir(ACCOUNT_ID, accountId);
        definir(CONVERSATION_ID, conversationId);
        try {
            return acao.get();
        } finally {
            definir(ACCOUNT_ID, conta);
            definir(CONVERSATION_ID, conversa);
        }
    }

    private static void definir(ThreadLocal<String> local, String valor) {
        if (valor == null) {
            local.remove();
        } else {
            local.set(valor);
        }
    }
}
