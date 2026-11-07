package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;

public interface OrcarPedidoUseCase {

    /** Responde o paciente na hora e processa o pedido em segundo plano. */
    void orcar(MensagemEntrada mensagem);

    /** Calcula pela tabela de preços os exames listados pelo atendente, responde em nota privada e leva ao funil. */
    void orcar(Orcamento.Comando comando);

    /**
     * Pedido de orçamento por texto: se a mensagem cita exames da tabela, avisa o paciente, deixa a nota com o
     * cálculo, passa para o Faturamento e abre a oportunidade. Retorna false quando não achou exame (nada foi feito).
     */
    boolean orcarTexto(MensagemEntrada mensagem);
}
