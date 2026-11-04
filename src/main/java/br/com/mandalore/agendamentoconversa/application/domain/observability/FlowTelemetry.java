package br.com.mandalore.agendamentoconversa.application.domain.observability;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.FlowEventStore;

@Component
public class FlowTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(FlowTelemetry.class);
    private static final int MAX_TEXT = 1000;

    private final List<FlowEventStore> stores;

    public FlowTelemetry(List<FlowEventStore> stores) {
        this.stores = stores;
    }

    public void record(FlowEvent event) {
        FlowEvent safeEvent = sanitize(event);
        for (FlowEventStore store : stores) {
            try {
                store.save(safeEvent);
            } catch (RuntimeException ex) {
                LOG.warn(
                        "observability_write_failed component={} stage={} error_type={}",
                        safeEvent.component(),
                        safeEvent.stage(),
                        ex.getClass().getSimpleName()
                );
                LOG.debug("observability_write_failed_details", ex);
            }
        }
    }

    public Optional<FlowReference> findByMessageSid(String messageSid) {
        if (messageSid == null || messageSid.isBlank()) {
            return Optional.empty();
        }
        for (FlowEventStore store : stores) {
            try {
                Optional<FlowReference> reference = store.findByMessageSid(messageSid);
                if (reference.isPresent()) {
                    return reference;
                }
            } catch (RuntimeException ex) {
                LOG.warn("observability_lookup_failed error_type={}", ex.getClass().getSimpleName());
                LOG.debug("observability_lookup_failed_details", ex);
            }
        }
        return Optional.empty();
    }

    private FlowEvent sanitize(FlowEvent event) {
        return new FlowEvent(
                limit(event.correlationId()),
                limit(event.conversationId()),
                limit(event.component()),
                limit(event.stage()),
                limit(event.status()),
                limit(event.intent()),
                limit(event.action()),
                limit(event.eventName()),
                limit(event.messageSid()),
                limit(event.deliveryStatus()),
                limit(event.businessResult()),
                event.httpStatus(),
                limit(event.errorCode()),
                limit(event.errorType()),
                limit(event.errorMessage()),
                event.durationMs(),
                event.details()
        );
    }

    private String limit(String value) {
        if (value == null || value.length() <= MAX_TEXT) {
            return value;
        }
        return value.substring(0, MAX_TEXT);
    }
}
