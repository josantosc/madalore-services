package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;

public interface SimularConversaUseCase {

    RespostaDialogflow enviar(String sessao, String texto);
}
