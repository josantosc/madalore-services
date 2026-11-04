package br.com.mandalore.agendamentoconversa.adapter.cliente;

import java.util.function.Supplier;

/**
 * Conta do Chatwoot da requisição em andamento. Escolhe o Oracle do Tasy daquele cliente.
 * Vale só na thread da requisição: chamada assíncrona não herda o valor.
 */
public final class ClienteAtual {

    private static final ThreadLocal<String> ACCOUNT_ID = new ThreadLocal<>();

    private ClienteAtual() {
    }

    public static String accountId() {
        return ACCOUNT_ID.get();
    }

    public static <T> T executar(String accountId, Supplier<T> acao) {
        String anterior = ACCOUNT_ID.get();
        ACCOUNT_ID.set(accountId);
        try {
            return acao.get();
        } finally {
            if (anterior == null) {
                ACCOUNT_ID.remove();
            } else {
                ACCOUNT_ID.set(anterior);
            }
        }
    }
}
