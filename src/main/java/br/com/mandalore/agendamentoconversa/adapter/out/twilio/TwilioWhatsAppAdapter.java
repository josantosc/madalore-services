package br.com.mandalore.agendamentoconversa.adapter.out.twilio;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.WhatsAppPort;
import br.com.mandalore.agendamentoconversa.application.domain.exception.WhatsAppProviderException;

@Component
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class TwilioWhatsAppAdapter implements WhatsAppPort {

    private final TwilioRestClient client;
    private final TwilioProperties properties;

    public TwilioWhatsAppAdapter(TwilioRestClient client, TwilioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String enviar(String destinatario, String texto) {
        MessageCreator creator = Message.creator(
                new PhoneNumber(whatsapp(destinatario)),
                new PhoneNumber(whatsapp(properties.getWhatsapp().getFrom())),
                texto
        );
        String statusCallback = properties.getStatusCallbackUrl();
        if (statusCallback != null && !statusCallback.isBlank()) {
            creator.setStatusCallback(URI.create(statusCallback));
        }
        try {
            return creator.create(client).getSid();
        } catch (ApiException ex) {
            throw new WhatsAppProviderException(
                    ex.getCode() == null ? null : String.valueOf(ex.getCode()),
                    ex.getStatusCode(),
                    ex.getMessage(),
                    ex
            );
        }
    }

    private String whatsapp(String numero) {
        if (numero == null || numero.isBlank()) {
            throw new IllegalArgumentException("Número WhatsApp ausente");
        }
        String normalizado = numero.trim();
        return normalizado.startsWith("whatsapp:")
                ? normalizado
                : "whatsapp:" + normalizado;
    }
}
