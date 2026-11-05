package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;

public interface ChatwootPort {
    void publicar(String accountId, String conversationId, String texto);

    void abrirAtendente(String accountId, String conversationId);

    /** Retorna false quando o setor não tem equipe configurada. */
    boolean atribuirEquipe(String accountId, String conversationId, Setor setor);
}
