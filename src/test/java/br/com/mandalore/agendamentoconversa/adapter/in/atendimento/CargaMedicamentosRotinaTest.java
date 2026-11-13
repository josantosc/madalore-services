package br.com.mandalore.agendamentoconversa.adapter.in.atendimento;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.MedicamentoRotinaUseCase;

class CargaMedicamentosRotinaTest {

    private final MedicamentoRotinaUseCase medicamentos = mock(MedicamentoRotinaUseCase.class);

    @Test
    void tentaTodasAsContasCadastradasEAPadrao() {
        ClientesProperties clientes = new ClientesProperties(Map.of(
                "7", new ClientesProperties.Cliente(null, null, null, "mandalore", null),
                "8", new ClientesProperties.Cliente(null, null, null, "tasy", null)));

        new CargaMedicamentosRotina(medicamentos, clientes, "1").carregar();

        verify(medicamentos).carregarPadraoSeNova("1");
        verify(medicamentos).carregarPadraoSeNova("7");
        verify(medicamentos).carregarPadraoSeNova("8");
    }

    @Test
    void falhaNumaContaNaoImpedeAsOutrasNemASubida() {
        ClientesProperties clientes = new ClientesProperties(Map.of(
                "7", new ClientesProperties.Cliente(null, null, null, "mandalore", null)));
        when(medicamentos.carregarPadraoSeNova("1")).thenThrow(new IllegalStateException("tabela ausente"));

        assertThatCode(() -> new CargaMedicamentosRotina(medicamentos, clientes, "1").carregar()).doesNotThrowAnyException();

        verify(medicamentos).carregarPadraoSeNova("7");
    }
}
