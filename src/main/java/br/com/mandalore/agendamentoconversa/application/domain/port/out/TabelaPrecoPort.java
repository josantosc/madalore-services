package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;

public interface TabelaPrecoPort {

    List<Orcamento.ItemPreco> itens();

    /** Troca a tabela inteira numa transação. */
    void substituir(List<Orcamento.ItemPreco> itens, String usuario);

    /** Cria ou atualiza pelo código. */
    void salvar(Orcamento.ItemPreco item, String usuario);

    /** Retorna false quando o código não existe. */
    boolean remover(String codigo);
}
