package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.ExameLido;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.ItemPreco;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.Linha;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.PedidoLido;

class NotaOrcamentoTest {

    private static final List<ItemPreco> TABELA = List.of(
            new ItemPreco("HMG", "Hemograma completo", List.of("HMG"), new BigDecimal("25.00"), "1 dia útil"),
            new ItemPreco("GLI", "Glicose", List.of("Glicemia de jejum"), new BigDecimal("12.00"), ""),
            new ItemPreco("TSH", "TSH", List.of(), new BigDecimal("1200.00"), "3 dias úteis"));

    @Test
    void casaPeloItemDoGeminiPorSinonimoESemAcento() {
        PedidoLido pedido = new PedidoLido(true, List.of(
                new ExameLido("HMG", "Hemograma completo"),
                new ExameLido("Glicemia de Jejum", ""),
                new ExameLido("Hemograma", "hemograma completo"),
                new ExameLido("Vitamina D", "")));

        List<Linha> linhas = NotaOrcamento.casar(pedido, TABELA);

        assertThat(linhas).extracting(l -> l.item() == null ? null : l.item().codigo())
                .containsExactly("HMG", "GLI", null);
        assertThat(linhas.get(2).lido()).isEqualTo("Vitamina D");
    }

    @Test
    void notaTrazTextoParaOPacienteTotalEPendencias() {
        PedidoLido pedido = new PedidoLido(true, List.of(
                new ExameLido("HMG", "Hemograma completo"),
                new ExameLido("TSH", "TSH"),
                new ExameLido("Vitamina D", "")));

        String nota = NotaOrcamento.nota(pedido, NotaOrcamento.casar(pedido, TABELA));

        assertThat(nota)
                .contains("Confira com a foto")
                .contains("- Hemograma completo: R$ 25,00 (resultado em 1 dia útil)")
                .contains("- TSH: R$ 1.200,00 (resultado em 3 dias úteis)")
                .contains("Total: R$ 1.225,00")
                .contains("Sem preço na tabela (verificar):\n- Vitamina D");
    }

    @Test
    void imagemQueNaoEPedido() {
        PedidoLido pedido = new PedidoLido(false, List.of());

        assertThat(NotaOrcamento.nota(pedido, List.of())).isEqualTo(NotaOrcamento.NAO_E_PEDIDO);
    }

    @Test
    void pedidoSemExamesLegiveis() {
        PedidoLido pedido = new PedidoLido(true, List.of());

        assertThat(NotaOrcamento.nota(pedido, List.of())).isEqualTo(NotaOrcamento.SEM_EXAMES);
    }

    @Test
    void soExamesSemPrecoNaoMostraTextoParaPaciente() {
        PedidoLido pedido = new PedidoLido(true, List.of(new ExameLido("Vitamina D", "")));

        String nota = NotaOrcamento.nota(pedido, NotaOrcamento.casar(pedido, TABELA));

        assertThat(nota).doesNotContain("Texto para o paciente").contains("- Vitamina D");
    }
}
