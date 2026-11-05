package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;

public interface CuradoriaUseCase {

    Curadoria.Indicadores indicadores(int dias);

    List<Curadoria.Frase> fila(int dias);

    List<String> intents();

    /** Registra a decisão; em ENSINAR inclui a frase no Dialogflow. Devolve false se a frase já existia. */
    boolean decidir(Curadoria.Decisao decisao);
}
