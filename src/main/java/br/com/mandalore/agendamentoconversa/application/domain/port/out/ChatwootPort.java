package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;

public interface ChatwootPort {
    void publicar(String accountId, String conversationId, String texto);

    /** Nota visível só para a equipe no painel. */
    void notaPrivada(String accountId, String conversationId, String texto);

    /** Põe ou tira uma etiqueta da conversa sem mexer nas outras. */
    void etiqueta(String accountId, String conversationId, String etiqueta, boolean presente);

    void abrirAtendente(String accountId, String conversationId);

    /** Retorna false quando o setor não tem equipe configurada. */
    boolean atribuirEquipe(String accountId, String conversationId, Setor setor);

    Orcamento.Arquivo baixar(String url);
}
