package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;

public interface AgendamentoEscritaPort {

    ResultadoEscrita agendar(PedidoAgendar pedido);

    ResultadoEscrita confirmar(String chaveIdempotencia, String pacienteId, String nrSequencia);

    ResultadoEscrita cancelar(String chaveIdempotencia, String pacienteId, String nrSequencia);

    ResultadoEscrita remarcar(String chaveIdempotencia, String pacienteId, String nrAtual, PedidoAgendar novoHorario);
}
