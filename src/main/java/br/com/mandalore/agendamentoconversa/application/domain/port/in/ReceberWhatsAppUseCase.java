package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemWhatsApp;

public interface ReceberWhatsAppUseCase {

    void receber(MensagemWhatsApp mensagem);
}
