package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import br.com.mandalore.agendamentoconversa.application.domain.model.FulfillmentCommand;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoOperacao;

public interface ExecutarFulfillmentUseCase {
    ResultadoOperacao executar(FulfillmentCommand command);
}
