package br.com.mandalore.agendamentoconversa.application.domain.observability;

import java.util.Arrays;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ApplicationLifecycleTelemetry {

    private final FlowTelemetry telemetry;
    private final Environment environment;

    public ApplicationLifecycleTelemetry(FlowTelemetry telemetry, Environment environment) {
        this.telemetry = telemetry;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        telemetry.record(FlowEvent.builder("MANDALORE", "application.ready", "SUCCESS")
                .detail("profiles", Arrays.asList(environment.getActiveProfiles()))
                .build());
    }

    @EventListener(ContextClosedEvent.class)
    public void stopping() {
        telemetry.record(FlowEvent.builder("MANDALORE", "application.stopping", "SUCCESS")
                .build());
    }
}
