package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.ItemPreco;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.TabelaPrecoPort;

class TabelaPrecoServiceTest {

    private final TabelaPrecoPort porta = mock(TabelaPrecoPort.class);
    private final TabelaPrecoService service = new TabelaPrecoService(porta);

    @Test
    void salvarNormalizaAntesDeGravar() {
        service.salvar(new ItemPreco(" HMG ", " Hemograma\ncompleto ", Arrays.asList(" HMG", "", "HMG", "Hemo"),
                new BigDecimal("25.5"), null), "ana");

        verify(porta).salvar(new ItemPreco("HMG", "Hemograma completo", List.of("HMG", "Hemo"),
                new BigDecimal("25.50"), ""), "ana");
    }

    @Test
    void salvarRecusaItemInvalidoSemGravar() {
        assertThatThrownBy(() -> service.salvar(new ItemPreco("A B", "X", null, BigDecimal.ONE, ""), "ana"))
                .hasMessageContaining("Código");
        assertThatThrownBy(() -> service.salvar(new ItemPreco("A", " ", null, BigDecimal.ONE, ""), "ana"))
                .hasMessageContaining("Nome");
        assertThatThrownBy(() -> service.salvar(new ItemPreco("A", "X", List.of("a|b"), BigDecimal.ONE, ""), "ana"))
                .hasMessageContaining("Sinônimos");
        assertThatThrownBy(() -> service.salvar(new ItemPreco("A", "X", null, new BigDecimal("-1"), ""), "ana"))
                .hasMessageContaining("Valor");
        assertThatThrownBy(() -> service.salvar(new ItemPreco("A", "X", null, null, ""), "ana"))
                .hasMessageContaining("Valor");

        verify(porta, never()).salvar(any(), anyString());
    }

    @Test
    void importarSubstituiComOUsuarioERecusaArquivoSemItens() {
        int itens = service.importar("codigo;nome;valor\nGLI;Glicose;12\n".getBytes(), "ana");

        assertThat(itens).isEqualTo(1);
        verify(porta).substituir(List.of(new ItemPreco("GLI", "Glicose", List.of(), new BigDecimal("12.00"), "")), "ana");
        assertThatThrownBy(() -> service.importar("codigo;nome;valor\n".getBytes(), "ana"))
                .hasMessageContaining("Nenhum item");
    }

    @Test
    void removerRepassaOResultado() {
        when(porta.remover("GLI")).thenReturn(true);

        assertThat(service.remover("GLI", "ana")).isTrue();
        assertThat(service.remover("XXX", "ana")).isFalse();
    }
}
