package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;

public interface TreinamentoPort {

    /** Intents do agente com as frases de treino e os contextos de entrada. */
    List<Curadoria.IntentAgente> intents();

    /** Inclui a frase de treino na intent; devolve false se ela já existia. */
    boolean ensinar(String intent, String frase);
}
