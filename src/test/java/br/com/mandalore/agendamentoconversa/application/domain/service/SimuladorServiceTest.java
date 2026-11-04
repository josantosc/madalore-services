package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;

@ExtendWith(MockitoExtension.class)
class SimuladorServiceTest {

    @Mock
    private DialogflowPort dialogflow;

    @InjectMocks
    private SimuladorService service;

    @Test
    void usaSessaoPropriaDoSimulador() {
        when(dialogflow.detectar("cw-sim-ab12", "oi"))
                .thenReturn(new RespostaDialogflow("texto.saudacao", "Olá!"));

        RespostaDialogflow resposta = service.enviar("ab12", "oi");

        assertThat(resposta.intent()).isEqualTo("texto.saudacao");
        assertThat(resposta.texto()).isEqualTo("Olá!");
    }

    @Test
    void textoVazioNaoChamaDialogflow() {
        RespostaDialogflow resposta = service.enviar("ab12", "   ");

        assertThat(resposta.texto()).isEqualTo(SimuladorService.SEM_TEXTO);
        verify(dialogflow, never()).detectar(any(), any());
    }
}
