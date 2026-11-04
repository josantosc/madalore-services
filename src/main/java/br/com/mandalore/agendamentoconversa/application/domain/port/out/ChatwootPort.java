package br.com.mandalore.agendamentoconversa.application.domain.port.out;

public interface ChatwootPort {
    void publicar(String accountId, String conversationId, String texto);

    void abrirAtendente(String accountId, String conversationId);
}
