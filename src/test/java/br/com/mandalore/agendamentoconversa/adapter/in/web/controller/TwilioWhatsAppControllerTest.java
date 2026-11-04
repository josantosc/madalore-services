package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import br.com.mandalore.agendamentoconversa.adapter.in.web.twilio.TwilioWebhookValidator;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemWhatsApp;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberWhatsAppUseCase;

@ExtendWith(MockitoExtension.class)
class TwilioWhatsAppControllerTest {

    @Mock
    private ReceberWhatsAppUseCase useCase;

    @Mock
    private TwilioWebhookValidator validator;

    @Mock
    private FlowTelemetry telemetry;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new TwilioWhatsAppController(useCase, validator, telemetry)
        ).build();
    }

    @Test
    void recebeWebhookAssinado() throws Exception {
        MultiValueMap<String, String> parametros = parametros();
        when(validator.validarMensagem(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("assinatura")
        )).thenReturn(true);

        mvc.perform(post("/twilio/whatsapp")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", "assinatura")
                        .params(parametros))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response/>"
                ));

        ArgumentCaptor<MensagemWhatsApp> captor = ArgumentCaptor.forClass(MensagemWhatsApp.class);
        verify(useCase).receber(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().messageSid()).isEqualTo("SM1");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().texto()).isEqualTo("Olá");
    }

    @Test
    void rejeitaWebhookComAssinaturaInvalida() throws Exception {
        when(validator.validarMensagem(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(false);

        mvc.perform(post("/twilio/whatsapp")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(parametros()))
                .andExpect(status().isForbidden());

        verify(useCase, never()).receber(org.mockito.ArgumentMatchers.any());
    }

    private MultiValueMap<String, String> parametros() {
        MultiValueMap<String, String> parametros = new LinkedMultiValueMap<>();
        parametros.add("MessageSid", "SM1");
        parametros.add("From", "whatsapp:+5599999999999");
        parametros.add("To", "whatsapp:+14155238886");
        parametros.add("Body", "Olá");
        return parametros;
    }
}
