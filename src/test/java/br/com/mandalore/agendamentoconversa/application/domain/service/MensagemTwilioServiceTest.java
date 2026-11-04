package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.mandalore.agendamentoconversa.application.domain.exception.WhatsAppProviderException;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemWhatsApp;
import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.WhatsAppPort;

@ExtendWith(MockitoExtension.class)
class MensagemTwilioServiceTest {

    @Mock
    private DialogflowPort dialogflow;

    @Mock
    private WhatsAppPort whatsApp;

    @Mock
    private FlowTelemetry telemetry;

    private MensagemTwilioService service;

    @BeforeEach
    void setUp() {
        service = new MensagemTwilioService(dialogflow, whatsApp, telemetry);
    }

    @Test
    void processaMensagemRecebidaUmaUnicaVez() {
        MensagemWhatsApp mensagem = mensagem("SM1", "Olá");
        when(dialogflow.detectar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("Olá")))
                .thenReturn(new RespostaDialogflow("texto.saudacao", "Olá! Como posso ajudar?"));

        service.receber(mensagem);
        service.receber(mensagem);

        verify(dialogflow, times(1))
                .detectar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("Olá"));
        verify(whatsApp, times(1))
                .enviar("whatsapp:+5599999999999", "Olá! Como posso ajudar?");
    }

    @Test
    void mensagemSemTextoSolicitaDigitacao() {
        service.receber(mensagem("SM2", " "));

        verify(dialogflow, never()).detectar(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(whatsApp).enviar(
                "whatsapp:+5599999999999",
                MensagemTwilioService.SEM_TEXTO
        );
    }

    @Test
    void ignoraMensagemSemSidValido() {
        service.receber(new MensagemWhatsApp(
                null,
                "whatsapp:+5599999999999",
                "whatsapp:+14155238886",
                "Olá"
        ));

        verify(dialogflow, never()).detectar(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(whatsApp, never()).enviar(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void registraCodigoSincronoDaTwilio() {
        MensagemWhatsApp mensagem = mensagem("SM3", "Olá");
        when(dialogflow.detectar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("Olá")))
                .thenReturn(new RespostaDialogflow("texto.saudacao", "Olá!"));
        when(whatsApp.enviar("whatsapp:+5599999999999", "Olá!"))
                .thenThrow(new WhatsAppProviderException("20003", 401, "Authentication failed", null));
        when(whatsApp.enviar("whatsapp:+5599999999999", MensagemTwilioService.FALHA))
                .thenReturn("SM-FALLBACK");

        service.receber(mensagem);

        ArgumentCaptor<FlowEvent> captor = ArgumentCaptor.forClass(FlowEvent.class);
        verify(telemetry, atLeastOnce()).record(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getAllValues())
                .anySatisfy(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.component()).isEqualTo("TWILIO");
                    org.assertj.core.api.Assertions.assertThat(event.stage()).isEqualTo("message.send");
                    org.assertj.core.api.Assertions.assertThat(event.errorCode()).isEqualTo("20003");
                    org.assertj.core.api.Assertions.assertThat(event.httpStatus()).isEqualTo(401);
                });
    }

    private MensagemWhatsApp mensagem(String sid, String texto) {
        return new MensagemWhatsApp(
                sid,
                "whatsapp:+5599999999999",
                "whatsapp:+14155238886",
                texto
        );
    }
}
