package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.TabelaPrecoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.TabelaPrecoPort;

@Service
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class TabelaPrecoService implements TabelaPrecoUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(TabelaPrecoService.class);

    private final TabelaPrecoPort tabela;

    public TabelaPrecoService(TabelaPrecoPort tabela) {
        this.tabela = tabela;
    }

    @Override
    public List<Orcamento.ItemPreco> itens() {
        return tabela.itens();
    }

    @Override
    public int importar(byte[] csv, String usuario) {
        List<Orcamento.ItemPreco> itens = TabelaPrecoCsv.ler(csv);
        if (itens.isEmpty()) {
            throw new IllegalArgumentException("Nenhum item no arquivo");
        }
        tabela.substituir(itens, usuario);
        LOG.info("tabela de precos importada itens={} usuario={}", itens.size(), usuario);
        return itens.size();
    }

    @Override
    public byte[] exportar() {
        return TabelaPrecoCsv.escrever(tabela.itens());
    }

    @Override
    public void salvar(Orcamento.ItemPreco item, String usuario) {
        Orcamento.ItemPreco valido = ValidacaoPreco.validar(item);
        String anterior = tabela.itens().stream()
                .filter(i -> i.codigo().equals(valido.codigo()))
                .findFirst()
                .map(i -> i.valor().toPlainString())
                .orElse("novo");
        tabela.salvar(valido, usuario);
        LOG.info("tabela de precos item={} valor {} -> {} usuario={}",
                valido.codigo(), anterior, valido.valor().toPlainString(), usuario);
    }

    @Override
    public boolean remover(String codigo, String usuario) {
        boolean removido = tabela.remover(codigo);
        if (removido) {
            LOG.info("tabela de precos item={} removido usuario={}", codigo, usuario);
        }
        return removido;
    }
}
