package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.twilio.TwilioWebhookValidator;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemWhatsApp;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowReference;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberWhatsAppUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.service.Textos;

@RestController
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class TwilioWhatsAppController {

    private static final Logger LOG = LoggerFactory.getLogger(TwilioWhatsAppController.class);
    private static final String TWIML_VAZIO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response/>";

    private final ReceberWhatsAppUseCase receberWhatsApp;
    private final TwilioWebhookValidator validator;
    private final FlowTelemetry telemetry;

    public TwilioWhatsAppController(
            ReceberWhatsAppUseCase receberWhatsApp,
            TwilioWebhookValidator validator,
            FlowTelemetry telemetry
    ) {
        this.receberWhatsApp = receberWhatsApp;
        this.validator = validator;
        this.telemetry = telemetry;
    }

    @PostMapping(
            value = "/twilio/whatsapp",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE
    )
    public ResponseEntity<String> receber(
            @RequestHeader(name = "X-Twilio-Signature", required = false) String assinatura,
            @RequestParam MultiValueMap<String, String> parametros
    ) {
        String messageSid = parametros.getFirst("MessageSid");
        String conversationId = conversationId(parametros.getFirst("From"));
        if (!validator.validarMensagem(parametros, assinatura)) {
            telemetry.record(FlowEvent.builder("TWILIO", "webhook.signature", "ERROR")
                    .correlation(conversationId, conversationId)
                    .message(messageSid, null)
                    .httpStatus(HttpStatus.FORBIDDEN.value())
                    .error("INVALID_SIGNATURE", "SecurityException", "Assinatura Twilio inválida")
                    .build());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        MensagemWhatsApp mensagem = new MensagemWhatsApp(
                messageSid,
                parametros.getFirst("From"),
                parametros.getFirst("To"),
                parametros.getFirst("Body")
        );
        if (!mensagem.valida()) {
            telemetry.record(FlowEvent.builder("TWILIO", "webhook.validation", "ERROR")
                    .correlation(conversationId, conversationId)
                    .message(messageSid, null)
                    .httpStatus(HttpStatus.BAD_REQUEST.value())
                    .error("INVALID_PAYLOAD", "IllegalArgumentException", "Payload Twilio incompleto")
                    .build());
            return ResponseEntity.badRequest().build();
        }
        telemetry.record(FlowEvent.builder("TWILIO", "webhook.received", "SUCCESS")
                .correlation(conversationId, conversationId)
                .message(messageSid, "received")
                .httpStatus(HttpStatus.OK.value())
                .build());
        receberWhatsApp.receber(mensagem);
        return ResponseEntity.ok(TWIML_VAZIO);
    }

    @PostMapping(
            value = "/twilio/whatsapp/status",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public ResponseEntity<Void> status(
            @RequestHeader(name = "X-Twilio-Signature", required = false) String assinatura,
            @RequestParam MultiValueMap<String, String> parametros
    ) {
        String messageSid = parametros.getFirst("MessageSid");
        FlowReference reference = telemetry.findByMessageSid(messageSid)
                .orElse(new FlowReference(messageSid, null));
        if (!validator.validarStatus(parametros, assinatura)) {
            telemetry.record(FlowEvent.builder("TWILIO", "delivery.signature", "ERROR")
                    .correlation(reference.correlationId(), reference.conversationId())
                    .message(messageSid, parametros.getFirst("MessageStatus"))
                    .httpStatus(HttpStatus.FORBIDDEN.value())
                    .error("INVALID_SIGNATURE", "SecurityException", "Assinatura Twilio inválida")
                    .build());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String deliveryStatus = parametros.getFirst("MessageStatus");
        String errorCode = parametros.getFirst("ErrorCode");
        boolean failed = "failed".equalsIgnoreCase(deliveryStatus)
                || "undelivered".equalsIgnoreCase(deliveryStatus)
                || errorCode != null && !errorCode.isBlank();
        telemetry.record(FlowEvent.builder("TWILIO", "delivery.callback", failed ? "ERROR" : "SUCCESS")
                .correlation(reference.correlationId(), reference.conversationId())
                .message(messageSid, deliveryStatus)
                .httpStatus(HttpStatus.OK.value())
                .error(errorCode, failed ? "TwilioDeliveryException" : null, parametros.getFirst("ErrorMessage"))
                .detail(
                        "more_info",
                        errorCode == null || errorCode.isBlank()
                                ? null
                                : "https://www.twilio.com/docs/errors/" + errorCode
                )
                .build());
        LOG.info(
                "message_sid={} message_status={} error_code={}",
                messageSid,
                deliveryStatus,
                errorCode
        );
        return ResponseEntity.ok().build();
    }

    private String conversationId(String remetente) {
        return Textos.conversationId(Textos.sessionId("twilio", remetente));
    }
}
