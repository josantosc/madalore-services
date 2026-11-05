package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;

public interface LeitorPedidoPort {

    /** Lê os exames do pedido; {@code catalogo} são os nomes da tabela de preços, para casar abreviações. */
    Orcamento.PedidoLido ler(Orcamento.Arquivo arquivo, List<String> catalogo);
}
