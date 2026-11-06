package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;
import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;

class OrigemAgendaTest {

    private final ClientesProperties clientes = new ClientesProperties(Map.of(
            "3", new ClientesProperties.Cliente(null, null, null, "Mandalore", null),
            "4", new ClientesProperties.Cliente(null, null, null, "tasy", null)));

    @Test
    void contaEscolheAAgendaESemCadastroSegueAGlobal() {
        OrigemAgenda tasyPorPadrao = new OrigemAgenda(clientes, "tasy");
        OrigemAgenda mandalorePorPadrao = new OrigemAgenda(clientes, "mandalore");

        assertThat(tasyPorPadrao.mandalore("3")).isTrue();
        assertThat(tasyPorPadrao.mandalore("4")).isFalse();
        assertThat(tasyPorPadrao.mandalore("9")).isFalse();
        assertThat(mandalorePorPadrao.mandalore("4")).isFalse();
        assertThat(mandalorePorPadrao.mandalore("9")).isTrue();
        assertThat(mandalorePorPadrao.mandalore(null)).isTrue();
    }

    @Test
    void roteadorUsaAContaDaConversaEmAndamento() {
        AgendaConsultaPort tasy = mock(AgendaConsultaPort.class);
        AgendaConsultaPort mandalore = mock(AgendaConsultaPort.class);
        AgendaRoteamentoConfig.ConsultaRoteada roteada = new AgendaRoteamentoConfig.ConsultaRoteada(tasy, mandalore, new OrigemAgenda(clientes, "tasy"));

        ClienteAtual.executar("3", () -> roteada.exames());
        verify(mandalore).exames();
        verifyNoInteractions(tasy);

        ClienteAtual.executar("4", () -> roteada.exames());
        verify(tasy).exames();
    }

    @Test
    void padraoEhOAdaptadorQueNaoEhOMandalore() {
        AgendaConsultaPort tasy = mock(AgendaConsultaPort.class);
        AgendaConsultaPort mandalore = mock(AgendaConsultaPort.class);

        assertThat(AgendaRoteamentoConfig.padrao(List.of(mandalore, tasy), mandalore)).isSameAs(tasy);
        assertThat(AgendaRoteamentoConfig.padrao(List.of(mandalore), mandalore)).isSameAs(mandalore);
    }
}
