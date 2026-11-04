package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.exception.WhatsAppProviderException;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemWhatsApp;
import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberWhatsAppUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.WhatsAppPort;

@Service
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class MensagemTwilioService implements ReceberWhatsAppUseCase {

    static final String FALHA = "Não consegui continuar agora. Pode tentar novamente.";
    static final String SEM_TEXTO = "Consigo seguir por texto. Pode digitar a opção?";
    // A Twilio rejeita mensagens de WhatsApp acima de 1600 caracteres.
    static final int LIMITE_MENSAGEM = 1500;

    private static final Logger LOG = LoggerFactory.getLogger(MensagemTwilioService.class);

    private final DialogflowPort dialogflow;
    private final WhatsAppPort whatsApp;
    private final FlowTelemetry telemetry;
    private final Set<String> mensagensVistas = ConcurrentHashMap.newKeySet();

    public MensagemTwilioService(
            DialogflowPort dialogflow,
            WhatsAppPort whatsApp,
            FlowTelemetry telemetry
    ) {
        this.dialogflow = dialogflow;
        this.whatsApp = whatsApp;
        this.telemetry = telemetry;
    }

    @Override
    public void receber(MensagemWhatsApp mensagem) {
        if (!mensagem.valida() || !mensagensVistas.add(mensagem.messageSid())) {
            return;
        }

        long inicio = System.nanoTime();
        String intent = "";
        String status = "sucesso";
        String sessionId = Textos.sessionId("twilio", mensagem.remetente());
        String conversationId = Textos.conversationId(sessionId);
        try {
            if (mensagem.texto() == null || mensagem.texto().isBlank()) {
                String outboundSid = whatsApp.enviar(mensagem.remetente(), SEM_TEXTO);
                registrarEnvio(conversationId, mensagem.messageSid(), outboundSid, "message.no_text");
                return;
            }
            RespostaDialogflow resposta = dialogflow.detectar(
                    sessionId,
                    mensagem.texto()
            );
            intent = resposta.intent() == null ? "" : resposta.intent();
            String texto = resposta.texto() == null || resposta.texto().isBlank()
                    ? FALHA
                    : resposta.texto();
            for (String parte : Textos.partes(texto, LIMITE_MENSAGEM)) {
                String outboundSid = whatsApp.enviar(mensagem.remetente(), parte);
                registrarEnvio(conversationId, mensagem.messageSid(), outboundSid, "message.response");
            }
        } catch (RuntimeException ex) {
            status = "falha";
            LOG.warn("message_sid={} processamento_twilio_falhou", mensagem.messageSid(), ex);
            registrarFalhaProcessamento(ex, mensagem.messageSid(), conversationId, intent, inicio);
            publicarFalha(mensagem.remetente(), mensagem.messageSid(), conversationId);
        } finally {
            if (status.equals("sucesso")) {
                telemetry.record(FlowEvent.builder("MANDALORE", "message.process", "SUCCESS")
                        .correlation(conversationId, conversationId)
                        .intent(intent, action(intent))
                        .message(mensagem.messageSid(), "received")
                        .durationMs(elapsed(inicio))
                        .build());
            }
            LOG.info(
                    "message_sid={} intent={} latencia_ms={} status={}",
                    mensagem.messageSid(),
                    intent,
                    elapsed(inicio),
                    status
            );
        }
    }

    private void publicarFalha(String destinatario, String inboundSid, String conversationId) {
        try {
            String outboundSid = whatsApp.enviar(destinatario, FALHA);
            registrarEnvio(conversationId, inboundSid, outboundSid, "message.fallback");
        } catch (RuntimeException ex) {
            LOG.warn("falha_ao_publicar_resposta_twilio", ex);
            FlowEvent.Builder event = FlowEvent.builder("TWILIO", "message.fallback", "ERROR")
                    .correlation(conversationId, conversationId)
                    .message(inboundSid, null);
            if (ex instanceof WhatsAppProviderException provider) {
                event.httpStatus(provider.httpStatus())
                        .error(provider.providerCode(), provider);
            } else {
                event.error("TWILIO_SEND_FAILED", ex);
            }
            telemetry.record(event.build());
        }
    }

    private void registrarFalhaProcessamento(
            RuntimeException error,
            String inboundSid,
            String conversationId,
            String intent,
            long inicio
    ) {
        boolean twilio = error instanceof WhatsAppProviderException;
        FlowEvent.Builder event = FlowEvent.builder(
                        twilio ? "TWILIO" : "MANDALORE",
                        twilio ? "message.send" : "message.process",
                        "ERROR"
                )
                .correlation(conversationId, conversationId)
                .intent(intent, action(intent))
                .message(inboundSid, "received")
                .durationMs(elapsed(inicio));
        if (error instanceof WhatsAppProviderException provider) {
            event.httpStatus(provider.httpStatus())
                    .error(provider.providerCode(), provider);
        } else {
            event.error("MESSAGE_PROCESSING_FAILED", error);
        }
        telemetry.record(event.build());
    }

    private void registrarEnvio(String conversationId, String inboundSid, String outboundSid, String stage) {
        telemetry.record(FlowEvent.builder("TWILIO", stage, "SUCCESS")
                .correlation(conversationId, conversationId)
                .message(outboundSid, "accepted")
                .detail("inbound_message_sid", inboundSid)
                .build());
    }

    private String action(String intent) {
        if (intent == null || intent.isBlank()) {
            return "";
        }
        int separator = intent.lastIndexOf('.');
        return separator < 0 ? intent : intent.substring(separator + 1);
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
