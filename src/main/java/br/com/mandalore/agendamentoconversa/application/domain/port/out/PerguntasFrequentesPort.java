package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.Optional;

public interface PerguntasFrequentesPort {

    /** Texto da clínica da conversa para o tema (endereco, horario...), se cadastrado. */
    Optional<String> resposta(String tema);
}
