package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;

/** Clínica da conversa em andamento (conta do Chatwoot). Sem conta na sessão, usa agenda.conta-padrao. */
@Component
@ConditionalOnProperty(name = "agenda.enabled", havingValue = "true")
public class ContaAgenda {

    private final String padrao;

    public ContaAgenda(@Value("${agenda.conta-padrao:1}") String padrao) {
        this.padrao = padrao == null || padrao.isBlank() ? "1" : padrao.trim();
    }

    public String atual() {
        String conta = ClienteAtual.accountId();
        return conta == null || conta.isBlank() ? padrao : conta;
    }

    public String padrao() {
        return padrao;
    }
}
