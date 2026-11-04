package br.com.mandalore.agendamentoconversa.adapter.out.twilio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.twilio.http.TwilioRestClient;

@Configuration
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
@EnableConfigurationProperties(TwilioProperties.class)
public class TwilioConfig {

    @Bean
    public TwilioRestClient twilioRestClient(TwilioProperties properties) {
        exigir(properties.getAccountSid(), "TWILIO_ACCOUNT_SID");
        exigir(properties.getAuthToken(), "TWILIO_AUTH_TOKEN");
        exigir(properties.getWhatsapp().getFrom(), "TWILIO_WHATSAPP_FROM");
        exigir(properties.getWebhookUrl(), "TWILIO_WEBHOOK_URL");
        return new TwilioRestClient.Builder(
                properties.getAccountSid(),
                properties.getAuthToken()
        ).build();
    }

    private void exigir(String valor, String variavel) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(variavel + " ausente");
        }
    }
}
