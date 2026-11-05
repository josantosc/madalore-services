package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.math.BigDecimal;
import java.util.List;

public final class Orcamento {

    private Orcamento() {
    }

    /** Linha da tabela de preços particular. */
    public record ItemPreco(String codigo, String nome, List<String> sinonimos, BigDecimal valor, String prazo) {
        public ItemPreco {
            sinonimos = sinonimos == null ? List.of() : List.copyOf(sinonimos);
            prazo = prazo == null ? "" : prazo;
        }
    }

    /** Exame como escrito no pedido e, quando reconhecido, o nome do item da tabela. */
    public record ExameLido(String lido, String item) {
    }

    public record PedidoLido(boolean pedidoMedico, List<ExameLido> exames) {
        public PedidoLido {
            exames = exames == null ? List.of() : List.copyOf(exames);
        }
    }

    public record Arquivo(byte[] conteudo, String mimeType) {
    }

    /** Exame do pedido com o item da tabela; {@code item} nulo quando não há preço. */
    public record Linha(String lido, ItemPreco item) {
    }
}
