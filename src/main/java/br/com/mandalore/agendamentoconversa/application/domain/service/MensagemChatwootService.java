package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberMensagemUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;

@Service
public class MensagemChatwootService implements ReceberMensagemUseCase {

    static final String FALHA = "Não consegui continuar agora. Pode tentar de novo ou pedir um atendente.";
    static final String SEM_TEXTO = "Consigo seguir por texto. Pode digitar a opção?";

    private static final Logger LOG = LoggerFactory.getLogger(MensagemChatwootService.class);

    private final DialogflowPort dialogflow;
    private final ChatwootPort chatwoot;
    private final Set<String> mensagensVistas = ConcurrentHashMap.newKeySet();

    public MensagemChatwootService(DialogflowPort dialogflow, ChatwootPort chatwoot) {
        this.dialogflow = dialogflow;
        this.chatwoot = chatwoot;
    }

    @Override
    public void receber(MensagemEntrada mensagem) {
        if (!mensagem.aceita()) {
            return;
        }
        if ("open".equalsIgnoreCase(mensagem.status())) {
            LOG.info("conversation_id={} message_id={} intent= status=sucesso codigo=conversa_aberta latencia_ms=0",
                    mensagem.conversationId(), mensagem.messageId());
            return;
        }
        if (mensagem.messageId() != null && !mensagensVistas.add(mensagem.messageId())) {
            return;
        }
        long inicio = System.nanoTime();
        String status = "sucesso";
        String intent = "";
        try {
            if (mensagem.content() == null || mensagem.content().isBlank()) {
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), SEM_TEXTO);
                return;
            }
            RespostaDialogflow resposta = dialogflow.detectar(
                    Textos.sessionId(mensagem.accountId(), mensagem.conversationId()),
                    mensagem.content()
            );
            intent = resposta.intent() == null ? "" : resposta.intent();
            String texto = resposta.texto() == null || resposta.texto().isBlank() ? FALHA : resposta.texto();
            if (intent.startsWith("acao.")) {
                status = "falha";
            }
            Canal canal = mensagem.canal();
            for (String parte : Textos.partes(texto, canal.limiteCaracteres(), canal.limiteBytes())) {
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), parte);
            }
            if (intent.startsWith("transbordo.")) {
                chatwoot.abrirAtendente(mensagem.accountId(), mensagem.conversationId());
            }
        } catch (RuntimeException ex) {
            status = "falha";
            LOG.warn("conversation_id={} dialogflow indisponivel", mensagem.conversationId());
            chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), FALHA);
        } finally {
            LOG.info(
                    "conversation_id={} message_id={} canal={} intent={} codigo={} latencia_ms={} status={}",
                    mensagem.conversationId(),
                    mensagem.messageId(),
                    mensagem.canal(),
                    intent,
                    "falha".equals(status) ? "indisponivel" : "sucesso",
                    (System.nanoTime() - inicio) / 1_000_000,
                    status
            );
        }
    }
}
