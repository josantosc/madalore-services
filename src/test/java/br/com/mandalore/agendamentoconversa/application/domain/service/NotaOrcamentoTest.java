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
    void comandoCasaPorCodigoNomeParcialESinonimoSemRepetir() {
        List<String> termos = NotaOrcamento.termos(" hemograma; GLI,\n glicemia de jejum , tsh,, Vitamina D ");

        List<Linha> linhas = NotaOrcamento.casarTexto(termos, TABELA);

        assertThat(termos).containsExactly("hemograma", "GLI", "glicemia de jejum", "tsh", "Vitamina D");
        assertThat(linhas).extracting(l -> l.item() == null ? null : l.item().codigo())
                .containsExactly("HMG", "GLI", "TSH", null);
    }

    @Test
    void termoQueCasaComMaisDeUmItemFicaSemPrecoComSugestoes() {
        List<ItemPreco> tabela = List.of(
                new ItemPreco("T4L", "T4 livre", List.of(), new BigDecimal("30"), ""),
                new ItemPreco("T4T", "T4 total", List.of(), new BigDecimal("28"), ""));

        List<Linha> linhas = NotaOrcamento.casarTexto(List.of("t4"), tabela);
        assertThat(linhas).singleElement().satisfies(l -> assertThat(l.item()).isNull());

        linhas = NotaOrcamento.casarTexto(List.of("t4 l"), tabela);
        assertThat(linhas).singleElement().satisfies(l -> assertThat(l.item().codigo()).isEqualTo("T4L"));

        assertThat(NotaOrcamento.notaComando(NotaOrcamento.casarTexto(List.of("T4"), tabela), tabela))
                .contains("- T4 (na tabela: T4 livre; T4 total)")
                .doesNotContain("funil");
    }

    @Test
    void notaDoComandoTrazTotalEAvisaDoFunil() {
        String nota = NotaOrcamento.notaComando(NotaOrcamento.casarTexto(List.of("HMG", "tsh"), TABELA), TABELA);

        assertThat(nota)
                .startsWith("Orçamento calculado pela tabela de preços")
                .contains("Total: R$ 1.225,00")
                .contains("Valor registrado no funil");
        assertThat(NotaOrcamento.notaComando(List.of(), TABELA)).isEqualTo(NotaOrcamento.COMANDO_VAZIO);
    }

    @Test
    void acheExamesNaFraseDoPacienteNaOrdemSemRepetirNemPegarGenerico() {
        List<ItemPreco> tabela = List.of(
                new ItemPreco("HMG", "Hemograma completo", List.of(), new BigDecimal("25"), ""),
                new ItemPreco("T4L", "T4 livre", List.of(), new BigDecimal("30"), ""),
                new ItemPreco("T4", "T4", List.of(), new BigDecimal("20"), ""),
                new ItemPreco("TSH", "TSH", List.of("hormonio tireoestimulante"), new BigDecimal("28"), ""),
                new ItemPreco("EAS", "Exame de urina tipo 1", List.of("EAS"), new BigDecimal("15"), ""),
                new ItemPreco("VITD", "Vitamina D", List.of(), new BigDecimal("80"), ""),
                new ItemPreco("VB12", "Vitamina B12", List.of(), new BigDecimal("60"), ""));

        assertThat(NotaOrcamento.examesNaFrase("Quanto fica TSH, t4 livre e o hemograma? E o TSH de novo", tabela))
                .extracting(l -> l.item().codigo()).containsExactly("TSH", "T4L", "HMG");
        assertThat(NotaOrcamento.examesNaFrase("quanto custa o exame de vitamina?", tabela)).isEmpty();
        assertThat(NotaOrcamento.examesNaFrase("Hormônio tireoestimulante e EAS", tabela))
                .extracting(l -> l.item().codigo()).containsExactly("TSH", "EAS");
    }

    @Test
    void notaDoPedidoPorTextoAvisaQueExameForaDaTabelaNaoAparece() {
        String nota = NotaOrcamento.notaTexto(NotaOrcamento.examesNaFrase("hmg", TABELA));

        assertThat(nota).contains("que o paciente escreveu").contains("Total: R$ 25,00");
    }

    @Test
    void soExamesSemPrecoNaoMostraTextoParaPaciente() {
        PedidoLido pedido = new PedidoLido(true, List.of(new ExameLido("Vitamina D", "")));

        String nota = NotaOrcamento.nota(pedido, NotaOrcamento.casar(pedido, TABELA));

        assertThat(nota).doesNotContain("Texto para o paciente").contains("- Vitamina D");
    }
}
