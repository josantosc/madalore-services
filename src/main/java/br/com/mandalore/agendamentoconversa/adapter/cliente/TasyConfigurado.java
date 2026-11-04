package br.com.mandalore.agendamentoconversa.adapter.cliente;

import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Há Oracle do Tasy global ({@code tasy.datasource.url}) ou em pelo menos um cliente. */
public class TasyConfigurado implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return existe(Binder.get(context.getEnvironment()));
    }

    static boolean existe(Binder binder) {
        String global = binder.bind("tasy.datasource.url", String.class).orElse("");
        if (!global.isBlank()) {
            return true;
        }
        return binder.bind("mandalore.clientes", Bindable.mapOf(String.class, ClientesProperties.Cliente.class))
                .orElse(Map.of())
                .values()
                .stream()
                .anyMatch(cliente -> cliente.tasy() != null && cliente.tasy().configurado());
    }

    public static class Ausente implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !existe(Binder.get(context.getEnvironment()));
        }
    }
}
