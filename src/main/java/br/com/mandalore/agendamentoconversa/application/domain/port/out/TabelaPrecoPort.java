package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;

public interface TabelaPrecoPort {

    List<Orcamento.ItemPreco> itens();

    /** Troca a tabela inteira numa transação. */
    void substituir(List<Orcamento.ItemPreco> itens);
}
