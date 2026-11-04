package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;

public interface SimularChatwootUseCase {

    ConversaVisitante enviar(String sessao, String texto);

    ConversaVisitante ler(String sessao);
}
