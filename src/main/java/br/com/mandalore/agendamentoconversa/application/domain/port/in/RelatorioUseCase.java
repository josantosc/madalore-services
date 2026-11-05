package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;

public interface RelatorioUseCase {

    String texto(Relatorio.Tipo tipo);

    void enviar(Relatorio.Tipo tipo);
}
