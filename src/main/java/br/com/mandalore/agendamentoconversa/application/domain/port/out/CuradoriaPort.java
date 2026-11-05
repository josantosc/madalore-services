package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;

public interface CuradoriaPort {

    Curadoria.Indicadores indicadores(int dias, double limiteConfianca);

    /** Falas ainda sem decisão, das mais frequentes para as menos. */
    List<Curadoria.Frase> fila(int dias, double limiteConfianca);

    void registrar(Curadoria.Decisao decisao);
}
