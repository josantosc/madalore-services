package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.TabelaPrecoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.TabelaPrecoPort;

@Service
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class TabelaPrecoService implements TabelaPrecoUseCase {

    private final TabelaPrecoPort tabela;

    public TabelaPrecoService(TabelaPrecoPort tabela) {
        this.tabela = tabela;
    }

    @Override
    public int importar(byte[] csv) {
        List<Orcamento.ItemPreco> itens = TabelaPrecoCsv.ler(csv);
        if (itens.isEmpty()) {
            throw new IllegalArgumentException("Nenhum item no arquivo");
        }
        tabela.substituir(itens);
        return itens.size();
    }

    @Override
    public List<Orcamento.ItemPreco> itens() {
        return tabela.itens();
    }
}
