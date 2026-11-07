package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.OrcarPedidoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberMensagemUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;

@Service
public class MensagemChatwootService implements ReceberMensagemUseCase {

    static final String FALHA = "Não consegui continuar agora. Pode tentar de novo ou pedir um atendente.";
    static final String SEM_TEXTO = "Consigo seguir por texto. Pode digitar a opção?";
    static final String INTENT_VALORES = "acao.faq.valores";
    private static final String ORCAMENTO_TEXTO = "orcamento.texto";

    private static final Logger LOG = LoggerFactory.getLogger(MensagemChatwootService.class);
    private static final int LIMITE_ASSUNTOS = 10_000;

    private final DialogflowPort dialogflow;
    private final ChatwootPort chatwoot;
    private final Set<String> mensagensVistas = ConcurrentHashMap.newKeySet();
    private final Map<String, Setor> assuntos = new ConcurrentHashMap<>();
    /** Conversas em que o assistente acabou de responder sobre valores: a próxima mensagem pode trazer os exames. */
    private final Set<String> aguardandoExames = ConcurrentHashMap.newKeySet();
    private final ObjectProvider<OrcarPedidoUseCase> orcamentos;
    private final FlowTelemetry telemetry;

    public MensagemChatwootService(
            DialogflowPort dialogflow,
            ChatwootPort chatwoot,
            ObjectProvider<OrcarPedidoUseCase> orcamentos,
            FlowTelemetry telemetry
    ) {
        this.dialogflow = dialogflow;
        this.chatwoot = chatwoot;
        this.orcamentos = orcamentos;
        this.telemetry = telemetry;
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
            OrcarPedidoUseCase orcamento = orcamentos.getIfAvailable();
            if (orcamento != null && mensagem.documento().isPresent()) {
                intent = "orcamento.pedido";
                orcamento.orcar(mensagem);
                registrar(mensagem, "orcamento.pedido", null);
                return;
            }
            if (mensagem.content() == null || mensagem.content().isBlank()) {
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), SEM_TEXTO);
                return;
            }
            String chave = mensagem.accountId() + ":" + mensagem.conversationId();
            if (orcamento != null && aguardandoExames.remove(chave) && orcamento.orcarTexto(mensagem)) {
                intent = ORCAMENTO_TEXTO;
                registrar(mensagem, ORCAMENTO_TEXTO, Setor.FATURAMENTO);
                return;
            }
            RespostaDialogflow resposta = dialogflow.detectar(
                    Textos.sessionId(mensagem.accountId(), mensagem.conversationId()),
                    mensagem.content()
            );
            intent = resposta.intent() == null ? "" : resposta.intent();
            if (orcamento != null && INTENT_VALORES.equals(intent)) {
                if (orcamento.orcarTexto(mensagem)) {
                    intent = ORCAMENTO_TEXTO;
                    registrar(mensagem, ORCAMENTO_TEXTO, Setor.FATURAMENTO);
                    return;
                }
                if (aguardandoExames.size() >= LIMITE_ASSUNTOS) {
                    aguardandoExames.clear();
                }
                aguardandoExames.add(chave);
            }
            String texto = resposta.texto() == null || resposta.texto().isBlank() ? FALHA : resposta.texto();
            if (intent.startsWith("acao.")) {
                status = "falha";
            }
            Canal canal = mensagem.canal();
            for (String parte : Textos.partes(texto, canal.limiteCaracteres(), canal.limiteBytes())) {
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), parte);
            }
            Optional<Setor> assunto = Encaminhamento.assunto(mensagem.content());
            if (intent.startsWith("transbordo.")) {
                Setor setor = assunto.orElseGet(() -> Optional.ofNullable(assuntos.remove(chave)).orElse(Setor.RECEPCAO));
                atribuirEquipe(mensagem, setor);
                chatwoot.abrirAtendente(mensagem.accountId(), mensagem.conversationId());
                registrar(mensagem, "transbordo", setor);
            } else if (assunto.isPresent()) {
                if (assuntos.size() >= LIMITE_ASSUNTOS) {
                    assuntos.clear();
                }
                assuntos.put(chave, assunto.get());
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

    private void registrar(MensagemEntrada mensagem, String etapa, Setor setor) {
        telemetry.record(FlowEvent.builder("CHATWOOT", etapa, "SUCCESS")
                .correlation(mensagem.conversationId(), mensagem.conversationId())
                .detail("equipe", setor == null ? null : setor.name())
                .detail("canal", mensagem.canal() == null ? null : mensagem.canal().name())
                .build());
    }

    private void atribuirEquipe(MensagemEntrada mensagem, Setor setor) {
        try {
            if (chatwoot.atribuirEquipe(mensagem.accountId(), mensagem.conversationId(), setor)) {
                LOG.info("conversation_id={} equipe={}", mensagem.conversationId(), setor);
            }
        } catch (RuntimeException ex) {
            LOG.warn("conversation_id={} equipe={} falha ao atribuir equipe", mensagem.conversationId(), setor);
        }
    }
}
