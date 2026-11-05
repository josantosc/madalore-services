package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.ItemPreco;

class TabelaPrecoCsvTest {

    @Test
    void lePlanilhaDoExcelComPontoEVirgula() {
        String csv = """
                codigo;nome;sinonimos;valor;prazo
                HMG;Hemograma completo;HMG|Hemograma;R$ 25,00;1 dia útil
                RM;"Ressonância; crânio";;1.250,50;
                """;

        List<ItemPreco> itens = TabelaPrecoCsv.ler(csv.getBytes(Charset.forName("windows-1252")));

        assertThat(itens).hasSize(2);
        assertThat(itens.get(0).sinonimos()).containsExactly("HMG", "Hemograma");
        assertThat(itens.get(0).valor()).isEqualByComparingTo("25.00");
        assertThat(itens.get(0).prazo()).isEqualTo("1 dia útil");
        assertThat(itens.get(1).nome()).isEqualTo("Ressonância; crânio");
        assertThat(itens.get(1).valor()).isEqualByComparingTo(new BigDecimal("1250.50"));
    }

    @Test
    void aceitaVirgulaComoSeparadorEPontoDecimal() {
        String csv = "\uFEFFCódigo,Nome,Valor\nGLI,Glicose,12.5\n";

        List<ItemPreco> itens = TabelaPrecoCsv.ler(csv.getBytes(StandardCharsets.UTF_8));

        assertThat(itens).singleElement().satisfies(item -> {
            assertThat(item.codigo()).isEqualTo("GLI");
            assertThat(item.valor()).isEqualByComparingTo("12.50");
            assertThat(item.sinonimos()).isEmpty();
        });
    }

    @Test
    void recusaValorInvalidoComNumeroDaLinha() {
        String csv = "codigo;nome;valor\nGLI;Glicose;doze\n";

        assertThatThrownBy(() -> TabelaPrecoCsv.ler(csv.getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Linha 2");
    }

    @Test
    void recusaCodigoRepetidoECabecalhoErrado() {
        assertThatThrownBy(() -> TabelaPrecoCsv.ler("codigo;nome;valor\nA;X;1\nA;Y;2\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("repetido");
        assertThatThrownBy(() -> TabelaPrecoCsv.ler("exame;preco\nX;1\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Cabeçalho");
    }

    @Test
    void exportaNoFormatoQueAImportacaoLe() {
        List<ItemPreco> itens = List.of(
                new ItemPreco("HMG", "Hemograma completo", List.of("HMG", "Hemograma"), new BigDecimal("25"), "1 dia útil"),
                new ItemPreco("RM", "Ressonância; \"crânio\"", List.of(), new BigDecimal("1250.5"), ""));

        byte[] csv = TabelaPrecoCsv.escrever(itens);

        assertThat(new String(csv, StandardCharsets.UTF_8))
                .startsWith("\uFEFFcodigo;nome;sinonimos;valor;prazo\r\n")
                .contains("HMG;Hemograma completo;HMG|Hemograma;25,00;1 dia útil\r\n");
        assertThat(TabelaPrecoCsv.ler(csv)).isEqualTo(List.of(
                new ItemPreco("HMG", "Hemograma completo", List.of("HMG", "Hemograma"), new BigDecimal("25.00"), "1 dia útil"),
                new ItemPreco("RM", "Ressonância; \"crânio\"", List.of(), new BigDecimal("1250.50"), "")));
    }

    @Test
    void exportacaoNaoViraFormulaNoExcel() {
        byte[] csv = TabelaPrecoCsv.escrever(List.of(new ItemPreco("X", "=HYPERLINK(1)", List.of(), BigDecimal.ONE, "")));

        assertThat(new String(csv, StandardCharsets.UTF_8)).contains("X;'=HYPERLINK(1);;1,00;");
    }
}
