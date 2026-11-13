package br.com.mandalore.agendamentoconversa.adapter.cliente;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.LocaisChamadaPort;

/**
 * {@code mandalore.clientes.<id>.painel.locais} no clientes.properties, separados por vírgula
 * (ex.: {@code Consultório 1,Consultório 2,Recepção,Financeiro}).
 */
@Component
public class LocaisChamadaProperties implements LocaisChamadaPort {

    static final int LOCAL_MAX = 60;

    private final Environment ambiente;

    public LocaisChamadaProperties(Environment ambiente) {
        this.ambiente = ambiente;
    }

    @Override
    public List<String> locais(String conta) {
        String bruto = ambiente.getProperty("mandalore.clientes." + conta + ".painel.locais", "");
        return Arrays.stream(bruto.split(","))
                .map(String::strip)
                .filter(l -> !l.isEmpty() && l.length() <= LOCAL_MAX)
                .distinct()
                .toList();
    }
}
