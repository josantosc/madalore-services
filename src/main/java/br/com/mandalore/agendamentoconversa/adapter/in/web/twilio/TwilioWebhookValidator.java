package br.com.mandalore.agendamentoconversa.adapter.in.web.twilio;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

import com.twilio.security.RequestValidator;

import br.com.mandalore.agendamentoconversa.adapter.out.twilio.TwilioProperties;

@Component
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class TwilioWebhookValidator {

    private final RequestValidator validator;
    private final TwilioProperties properties;

    public TwilioWebhookValidator(TwilioProperties properties) {
        this.validator = new RequestValidator(properties.getAuthToken());
        this.properties = properties;
    }

    public boolean validarMensagem(
            MultiValueMap<String, String> parametros,
            String assinatura
    ) {
        return validar(properties.getWebhookUrl(), parametros, assinatura);
    }

    public boolean validarStatus(
            MultiValueMap<String, String> parametros,
            String assinatura
    ) {
        return validar(properties.getStatusCallbackUrl(), parametros, assinatura);
    }

    private boolean validar(
            String urlPublica,
            MultiValueMap<String, String> parametros,
            String assinatura
    ) {
        if (urlPublica == null || urlPublica.isBlank()
                || assinatura == null || assinatura.isBlank()) {
            return false;
        }
        Map<String, String> valores = new LinkedHashMap<>();
        parametros.forEach((nome, itens) -> {
            if (itens != null && !itens.isEmpty()) {
                valores.put(nome, itens.get(0));
            }
        });
        return validator.validate(urlPublica, valores, assinatura);
    }
}
