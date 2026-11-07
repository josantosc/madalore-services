package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;

public interface CuradoriaUseCase {

    Curadoria.Indicadores indicadores(int dias);

    List<Curadoria.Frase> fila(int dias);

    /** Intents em que faz sentido ensinar frase, com descrição, exemplos e o momento em que valem. */
    List<Curadoria.IntentInfo> intents();

    /** Até três intents parecidas com a fala, para a pessoa conferir antes de ensinar. */
    List<Curadoria.Sugestao> sugestoes(String fala);

    /** Registra a decisão; em ENSINAR inclui a frase no Dialogflow. Devolve false se a frase já existia. */
    boolean decidir(Curadoria.Decisao decisao);
}
