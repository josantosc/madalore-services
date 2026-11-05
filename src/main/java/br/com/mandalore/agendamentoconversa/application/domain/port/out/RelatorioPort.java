package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.time.Instant;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;

public interface RelatorioPort {

    /** Números de {@code inicio} (inclusive) a {@code fim} (exclusive). Pendentes: falas dos 7 dias antes de {@code fim}. */
    Relatorio.Numeros numeros(Instant inicio, Instant fim, double limiteConfianca);
}
