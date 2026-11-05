package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;

public interface OrcarPedidoUseCase {

    /** Responde o paciente na hora e processa o pedido em segundo plano. */
    void orcar(MensagemEntrada mensagem);
}
