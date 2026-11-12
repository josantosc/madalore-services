package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;
import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.OrigemAgendaPort;

/** Qual agenda o bot usa na conta da conversa: mandalore.clientes.&lt;id&gt;.agenda e, sem ela, agenda.origem. */
@Component
@ConditionalOnProperty(name = "agenda.enabled", havingValue = "true")
public class OrigemAgenda implements OrigemAgendaPort {

    public static final String MANDALORE = "mandalore";

    private final ClientesProperties clientes;
    private final String global;

    public OrigemAgenda(ClientesProperties clientes, @Value("${agenda.origem:tasy}") String global) {
        this.clientes = clientes;
        this.global = normalizar(global);
    }

    public boolean mandalore() {
        return mandalore(ClienteAtual.accountId());
    }

    @Override
    public boolean mandalore(String accountId) {
        String daConta = clientes.cliente(accountId)
                .map(ClientesProperties.Cliente::agenda)
                .map(OrigemAgenda::normalizar)
                .filter(origem -> !origem.isEmpty())
                .orElse(global);
        return MANDALORE.equals(daConta);
    }

    private static String normalizar(String origem) {
        return origem == null ? "" : origem.trim().toLowerCase(Locale.ROOT);
    }
}
