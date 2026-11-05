package br.com.mandalore.agendamentoconversa.adapter.cliente;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PerguntasFrequentesPropertiesTest {

    private final PerguntasFrequentesProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
            "mandalore.faq.endereco", "Rua Global, 1",
            "mandalore.faq.horario", "Das 7h às 18h",
            "mandalore.clientes.2.faq.endereco", "Rua da Conta 2, 50",
            "mandalore.clientes.2.faq.horario", " ",
            "mandalore.clientes.2.chatwoot-token", "tk-2"
    ))).bind("mandalore", PerguntasFrequentesProperties.class).get();

    @Test
    void contaSubstituiOTextoGlobal() {
        assertThat(properties.resposta("2", "endereco")).contains("Rua da Conta 2, 50");
    }

    @Test
    void temaVazioNaContaUsaOGlobal() {
        assertThat(properties.resposta("2", "horario")).contains("Das 7h às 18h");
    }

    @Test
    void contaSemCadastroOuSemContaUsaOGlobal() {
        assertThat(properties.resposta("9", "endereco")).contains("Rua Global, 1");
        assertThat(properties.resposta(null, "endereco")).contains("Rua Global, 1");
    }

    @Test
    void temaSemTextoEmLugarNenhum() {
        assertThat(properties.resposta("2", "estacionamento")).isEmpty();
    }
}
