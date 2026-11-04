package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;

public interface ReceberMensagemUseCase {
    void receber(MensagemEntrada mensagem);
}
