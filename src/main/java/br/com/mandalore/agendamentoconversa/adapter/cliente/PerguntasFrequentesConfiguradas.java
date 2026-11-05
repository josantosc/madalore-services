package br.com.mandalore.agendamentoconversa.adapter.cliente;

import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.PerguntasFrequentesPort;

@Component
public class PerguntasFrequentesConfiguradas implements PerguntasFrequentesPort {

    private final PerguntasFrequentesProperties properties;

    public PerguntasFrequentesConfiguradas(PerguntasFrequentesProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> resposta(String tema) {
        return properties.resposta(ClienteAtual.accountId(), tema);
    }
}
