package br.com.mandalore.agendamentoconversa.application.domain.observability;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.FlowEventStore;

@ExtendWith(MockitoExtension.class)
class FlowTelemetryTest {

    @Mock
    private FlowEventStore store;

    @Test
    void falhaNaTelemetriaNaoInterrompeFluxoPrincipal() {
        FlowEvent event = FlowEvent.builder("MANDALORE", "test", "SUCCESS").build();
        doThrow(new IllegalStateException("postgres offline")).when(store).save(event);

        FlowTelemetry telemetry = new FlowTelemetry(List.of(store));

        assertThatCode(() -> telemetry.record(event)).doesNotThrowAnyException();
    }

    @Test
    void encaminhaEventoParaStoreConfigurado() {
        FlowEvent event = FlowEvent.builder("TWILIO", "webhook.received", "SUCCESS").build();
        FlowTelemetry telemetry = new FlowTelemetry(List.of(store));

        telemetry.record(event);

        verify(store).save(event);
    }
}
