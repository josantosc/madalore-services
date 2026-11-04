package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.application.domain.model.ContextoConversa;

class ContextoCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContextoCodec codec = new ContextoCodec(mapper);

    @Test
    void preservaPacienteSemCadastroNosContextosDialogflow() {
        ContextoConversa original = new ContextoConversa();
        original.setSemCadastro(true);
        original.setCpf("12345678901");
        original.setNascimento(LocalDate.of(1988, 10, 19));
        original.setNome("Maria da Silva");

        ContextoConversa restaurado = codec.ler(mapper.valueToTree(
                codec.escrever("projects/p/agent/sessions/s1", original)
        ));

        assertThat(restaurado.isSemCadastro()).isTrue();
        assertThat(restaurado.temPaciente()).isFalse();
        assertThat(restaurado.temIdentidadeAgendamento()).isTrue();
        assertThat(restaurado.getCpf()).isEqualTo("12345678901");
        assertThat(restaurado.getNascimento()).isEqualTo(LocalDate.of(1988, 10, 19));
        assertThat(restaurado.getNome()).isEqualTo("Maria da Silva");
    }
}
