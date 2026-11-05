package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;

public interface TabelaPrecoUseCase {

    /** Substitui a tabela pelo CSV; retorna quantos itens foram gravados. */
    int importar(byte[] csv);

    List<Orcamento.ItemPreco> itens();
}
