package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;

public interface TabelaPrecoUseCase {

    List<Orcamento.ItemPreco> itens();

    /** Substitui a tabela pelo CSV; retorna quantos itens foram gravados. */
    int importar(byte[] csv, String usuario);

    /** CSV no mesmo formato da importação, pronto para abrir no Excel. */
    byte[] exportar();

    void salvar(Orcamento.ItemPreco item, String usuario);

    /** Retorna false quando o código não existe. */
    boolean remover(String codigo, String usuario);
}
