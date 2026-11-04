package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.Optional;

import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowReference;

public interface FlowEventStore {

    void save(FlowEvent event);

    Optional<FlowReference> findByMessageSid(String messageSid);
}
