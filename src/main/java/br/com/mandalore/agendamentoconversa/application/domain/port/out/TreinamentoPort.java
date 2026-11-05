package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;

public interface TreinamentoPort {

    /** Nomes (displayName) das intents do agente. */
    List<String> intents();

    /** Inclui a frase de treino na intent; devolve false se ela já existia. */
    boolean ensinar(String intent, String frase);
}
