package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
import br.com.mandalore.agendamentoconversa.application.domain.port.in.CheckinUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.OrcarPedidoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberMensagemUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;

@Service
public class MensagemChatwootService implements ReceberMensagemUseCase {

    static final String FALHA = "Não consegui continuar agora. Pode tentar de novo ou pedir um atendente.";
    static final String SEM_TEXTO = "Consigo seguir por texto. Pode digitar a opção?";
    static final String INTENT_VALORES = "acao.faq.valores";
    static final String INTENT_FALLBACK = "texto.fallback";
    /** Paciente encerra a conversa a qualquer momento ou depois de agendar. */
    static final String INTENT_ENCERRAR = "texto.conversa.encerrar";
    /** Opção "Orçamento de exames" do menu, antes e depois de identificar o paciente. */
    static final String INTENT_MENU_ORCAMENTO = "texto.menu.orcamento";
    static final String DICA_EXAMES = "Se não tiver o pedido, escreva aqui os nomes dos exames, por exemplo: "
            + "hemograma, glicose e TSH.";
    static final String SEM_PEDIDO = "Sem problema. Escreva aqui os nomes dos exames que você quer fazer, por "
            + "exemplo: hemograma, glicose e TSH.";
    static final String EXAMES_NAO_ACHADOS = "Não achei esses exames na nossa tabela. Passei sua mensagem para a "
            + "equipe do Faturamento, que te responde por aqui.";
    static final String URGENCIA = "Se for uma emergência, ligue agora para o SAMU (192) ou vá ao pronto-socorro "
            + "mais próximo. Este canal é para agendamentos e não acompanha urgências. Já avisei a equipe da clínica.";
    static final String SEGUNDA_FALHA = "Ainda não consegui entender. Vou chamar alguém da equipe para continuar "
            + "com você. A pessoa vê esta conversa, então não precisa repetir nada.";
    /** Respostas não entendidas seguidas antes de passar a conversa para a equipe. */
    static final int LIMITE_FALHAS = 2;
    private static final String ORCAMENTO_TEXTO = "orcamento.texto";
    /** Mensagens do paciente, depois da resposta de valores, em que o assistente ainda procura os exames. */
    static final int MENSAGENS_AGUARDANDO_EXAMES = 3;
    private static final Pattern SEM_PEDIDO_PADRAO = Pattern.compile(
            "\\b(n[aã]o|nao|nem|sem)\\b[^.?!]{0,30}\\b(pedido|guia|receita|requisi[cç][aã]o)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Logger LOG = LoggerFactory.getLogger(MensagemChatwootService.class);
    private static final int LIMITE_ASSUNTOS = 10_000;

    private final DialogflowPort dialogflow;
    private final ChatwootPort chatwoot;
    private final Set<String> mensagensVistas = ConcurrentHashMap.newKeySet();
    private final Map<String, Setor> assuntos = new ConcurrentHashMap<>();
    /** Conversas em que o assistente respondeu sobre valores, com as mensagens que ainda podem trazer os exames. */
    private final Map<String, Integer> aguardandoExames = new ConcurrentHashMap<>();
    private final Map<String, Integer> falhas = new ConcurrentHashMap<>();
    private final ObjectProvider<OrcarPedidoUseCase> orcamentos;
    private final ObjectProvider<CheckinUseCase> checkins;
    private final FlowTelemetry telemetry;

    public MensagemChatwootService(
            DialogflowPort dialogflow,
            ChatwootPort chatwoot,
            ObjectProvider<OrcarPedidoUseCase> orcamentos,
            ObjectProvider<CheckinUseCase> checkins,
            FlowTelemetry telemetry
    ) {
        this.dialogflow = dialogflow;
        this.chatwoot = chatwoot;
        this.orcamentos = orcamentos;
        this.checkins = checkins;
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
            if (Urgencia.detectar(mensagem.content())) {
                intent = "transbordo.urgencia";
                falhas.remove(chave);
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), URGENCIA);
                atribuirEquipe(mensagem, Setor.RECEPCAO);
                chatwoot.abrirAtendente(mensagem.accountId(), mensagem.conversationId());
                registrar(mensagem, "urgencia", Setor.RECEPCAO);
                return;
            }
            CheckinUseCase checkin = checkins.getIfAvailable();
            Optional<String> respostaCheckin = checkin == null ? Optional.empty()
                    : checkin.responder(mensagem.accountId(), mensagem.conversationId(), mensagem.content());
            if (respostaCheckin.isPresent()) {
                intent = "checkin";
                falhas.remove(chave);
                aguardandoExames.remove(chave);
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), respostaCheckin.get());
                registrar(mensagem, "checkin", null);
                return;
            }
            boolean aguardando = orcamento != null && consumirEspera(chave);
            if (aguardando) {
                if (orcamento.orcarTexto(mensagem)) {
                    aguardandoExames.remove(chave);
                    intent = ORCAMENTO_TEXTO;
                    registrar(mensagem, ORCAMENTO_TEXTO, Setor.FATURAMENTO);
                    return;
                }
                if (semPedido(mensagem.content())) {
                    intent = "orcamento.sem_pedido";
                    esperarExames(chave);
                    chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), SEM_PEDIDO);
                    return;
                }
            }
            RespostaDialogflow resposta = dialogflow.detectar(
                    Textos.sessionId(mensagem.accountId(), mensagem.conversationId()),
                    mensagem.content()
            );
            intent = resposta.intent() == null ? "" : resposta.intent();
            if (aguardando && INTENT_FALLBACK.equals(intent)) {
                aguardandoExames.remove(chave);
                intent = "orcamento.exames_nao_achados";
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), EXAMES_NAO_ACHADOS);
                atribuirEquipe(mensagem, Setor.FATURAMENTO);
                chatwoot.abrirAtendente(mensagem.accountId(), mensagem.conversationId());
                registrar(mensagem, "transbordo", Setor.FATURAMENTO);
                return;
            }
            if (!naoEntendeu(intent)) {
                falhas.remove(chave);
            } else if (contarFalha(chave) >= LIMITE_FALHAS) {
                falhas.remove(chave);
                intent = "transbordo.nao_entendido";
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), SEGUNDA_FALHA);
                Setor setor = Encaminhamento.assunto(mensagem.content())
                        .orElseGet(() -> Optional.ofNullable(assuntos.remove(chave)).orElse(Setor.RECEPCAO));
                atribuirEquipe(mensagem, setor);
                chatwoot.abrirAtendente(mensagem.accountId(), mensagem.conversationId());
                registrar(mensagem, "transbordo", setor);
                return;
            }
            String textoValores = null;
            if (orcamento != null && pedeOrcamento(intent)) {
                if (orcamento.orcarTexto(mensagem)) {
                    aguardandoExames.remove(chave);
                    intent = ORCAMENTO_TEXTO;
                    registrar(mensagem, ORCAMENTO_TEXTO, Setor.FATURAMENTO);
                    return;
                }
                esperarExames(chave);
                textoValores = comDicaDeExames(resposta.texto());
            }
            String texto = textoValores != null ? textoValores
                    : resposta.texto() == null || resposta.texto().isBlank() ? FALHA : resposta.texto();
            if (intent.startsWith("acao.")) {
                status = "falha";
            }
            Canal canal = mensagem.canal();
            for (String parte : Textos.partes(texto, canal.limiteCaracteres(), canal.limiteBytes())) {
                chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), parte);
            }
            if (intent.startsWith(INTENT_ENCERRAR)) {
                aguardandoExames.remove(chave);
                assuntos.remove(chave);
                encerrar(mensagem);
                return;
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

    private void encerrar(MensagemEntrada mensagem) {
        try {
            if (chatwoot.resolverSePendente(mensagem.accountId(), mensagem.conversationId())) {
                LOG.info("conversation_id={} encerrada pelo paciente", mensagem.conversationId());
            }
        } catch (RuntimeException ex) {
            LOG.warn("conversation_id={} falha ao resolver a conversa", mensagem.conversationId());
        }
        registrar(mensagem, "encerrada", null);
    }

    private void esperarExames(String chave) {
        if (aguardandoExames.size() >= LIMITE_ASSUNTOS) {
            aguardandoExames.clear();
        }
        aguardandoExames.put(chave, MENSAGENS_AGUARDANDO_EXAMES);
    }

    /** Desconta uma mensagem da espera; devolve se a conversa ainda esperava os exames. */
    private boolean consumirEspera(String chave) {
        boolean[] esperava = {false};
        aguardandoExames.computeIfPresent(chave, (k, restantes) -> {
            esperava[0] = true;
            return restantes > 1 ? restantes - 1 : null;
        });
        return esperava[0];
    }

    static boolean naoEntendeu(String intent) {
        return intent.startsWith("texto.") && intent.endsWith("fallback");
    }

    private int contarFalha(String chave) {
        if (falhas.size() >= LIMITE_ASSUNTOS) {
            falhas.clear();
        }
        return falhas.merge(chave, 1, Integer::sum);
    }

    static boolean pedeOrcamento(String intent) {
        return INTENT_VALORES.equals(intent) || intent.startsWith(INTENT_MENU_ORCAMENTO);
    }

    static boolean semPedido(String texto) {
        return texto != null && SEM_PEDIDO_PADRAO.matcher(texto).find();
    }

    /**
     * A resposta de valores vem do faq.yaml da clínica; sem convite para escrever os exames, o paciente sem pedido
     * não sabe que pode digitá-los.
     */
    static String comDicaDeExames(String texto) {
        if (texto == null || texto.isBlank()) {
            return DICA_EXAMES;
        }
        int posicao = texto.lastIndexOf(PerguntasFrequentesService.FECHAMENTO);
        String corpo = posicao < 0 ? texto : texto.substring(0, posicao);
        String normalizado = corpo.toLowerCase(java.util.Locale.ROOT);
        if (normalizado.contains("escrev") || normalizado.contains("digit")) {
            return texto;
        }
        String dica = corpo.stripTrailing() + "\n\n" + DICA_EXAMES;
        return posicao < 0 ? dica : dica + "\n\n" + texto.substring(posicao);
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
