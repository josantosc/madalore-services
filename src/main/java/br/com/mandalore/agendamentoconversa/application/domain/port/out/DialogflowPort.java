package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;

public interface DialogflowPort {
    RespostaDialogflow detectar(String sessionId, String texto);
}
