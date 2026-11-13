package br.com.mandalore.agendamentoconversa.adapter.cliente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LocaisChamadaPropertiesTest {

    @Test
    void separaPorVirgulaLimpaEIgnoraVaziosRepetidosELongos() {
        MockEnvironment ambiente = new MockEnvironment()
                .withProperty("mandalore.clientes.7.painel.locais",
                        " Consultório 1, ,Recepção,Consultório 1," + "x".repeat(61) + ",Financeiro ");

        assertThat(new LocaisChamadaProperties(ambiente).locais("7"))
                .containsExactly("Consultório 1", "Recepção", "Financeiro");
    }

    @Test
    void contaSemConfiguracaoDevolveListaVazia() {
        assertThat(new LocaisChamadaProperties(new MockEnvironment()).locais("8")).isEmpty();
    }
}
