package br.com.mandalore.agendamentoconversa.adapter.cliente;

import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.CheckinConfigPort;

/**
 * {@code mandalore.clientes.<id>.whatsapp} no clientes.properties (dígitos com DDI). O código do dia deriva do segredo
 * do webhook da clínica ({@code mandalore.clientes.<id>.webhook-secret}, senão {@code chatwoot.webhook-secret}).
 */
@Component
public class CheckinProperties implements CheckinConfigPort {

    static final int SEGREDO_MIN = 16;

    private final Environment ambiente;

    public CheckinProperties(Environment ambiente) {
        this.ambiente = ambiente;
    }

    @Override
    public Optional<String> whatsapp(String conta) {
        String numero = ambiente.getProperty("mandalore.clientes." + conta + ".whatsapp", "").replaceAll("\\D", "");
        return numero.length() >= 10 && numero.length() <= 15 ? Optional.of(numero) : Optional.empty();
    }

    @Override
    public Optional<String> segredo(String conta) {
        String segredo = ambiente.getProperty("mandalore.clientes." + conta + ".webhook-secret", "").trim();
        if (segredo.isEmpty()) {
            segredo = ambiente.getProperty("chatwoot.webhook-secret", "").trim();
        }
        return segredo.length() >= SEGREDO_MIN ? Optional.of(segredo) : Optional.empty();
    }
}
