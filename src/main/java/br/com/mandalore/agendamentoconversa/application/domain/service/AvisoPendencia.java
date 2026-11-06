package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaMandalorePort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;

/**
 * Avisa a recepção no Chatwoot: nota privada na conversa do paciente com o que lançar no sistema da clínica e a
 * etiqueta {@value #ETIQUETA}, que sai quando a conversa não tem mais pendência aberta, e a equipe de agendamento.
 * A conversa continua pendente, com o bot respondendo. Falha no Chatwoot não desfaz o agendamento: a pendência
 * continua na tela /agenda.
 */
@Component
@ConditionalOnProperty(name = {"agenda.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class AvisoPendencia {

    public static final String ETIQUETA = "lancar-sistema";

    private static final Logger LOG = LoggerFactory.getLogger(AvisoPendencia.class);

    private final ChatwootPort chatwoot;
    private final AgendaMandalorePort agenda;
    private final String url;
    private final Executor executor;

    @Autowired
    public AvisoPendencia(ChatwootPort chatwoot, AgendaMandalorePort agenda, @Value("${agenda.url:}") String url) {
        this(chatwoot, agenda, url, Executors.newVirtualThreadPerTaskExecutor());
    }

    AvisoPendencia(ChatwootPort chatwoot, AgendaMandalorePort agenda, String url, Executor executor) {
        this.chatwoot = chatwoot;
        this.agenda = agenda;
        this.url = url == null ? "" : url.trim().replaceAll("/$", "");
        this.executor = executor;
    }

    public void criadas(String conta, List<Agenda.Pendencia> pendencias) {
        List<Agenda.Pendencia> naConversa = pendencias.stream().filter(p -> vinculada(p.conversaId())).toList();
        if (naConversa.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            for (Agenda.Pendencia pendencia : naConversa) {
                tentar(pendencia.conversaId(), "nota", () -> chatwoot.notaPrivada(conta, pendencia.conversaId(), nota(pendencia)));
                tentar(pendencia.conversaId(), "etiqueta", () -> chatwoot.etiqueta(conta, pendencia.conversaId(), ETIQUETA, true));
            }
            naConversa.stream().map(Agenda.Pendencia::conversaId).distinct().forEach(conversa ->
                    tentar(conversa, "equipe", () -> chatwoot.atribuirEquipe(conta, conversa, Setor.AGENDAMENTO)));
        });
    }

    /** Depois de concluir, reabrir ou descartar: a etiqueta acompanha as pendências abertas da conversa. */
    public void atualizada(String conta, String conversaId) {
        if (!vinculada(conversaId)) {
            return;
        }
        executor.execute(() -> tentar(conversaId, "etiqueta", () -> chatwoot.etiqueta(
                conta, conversaId, ETIQUETA, agenda.pendenciasAbertasDaConversa(conta, conversaId) > 0)));
    }

    String nota(Agenda.Pendencia pendencia) {
        String texto = "Pendência da agenda: " + pendencia.resumo()
                + "\nPrazo: " + TextoPendencia.dataHora(pendencia.prazo());
        if (!url.isEmpty()) {
            texto += "\nMarque como feito em " + url + "/agenda?pendencia=" + pendencia.id();
        }
        return texto;
    }

    private static boolean vinculada(String conversaId) {
        return conversaId != null && !conversaId.isBlank();
    }

    private static void tentar(String conversationId, String etapa, Runnable acao) {
        try {
            acao.run();
        } catch (RuntimeException ex) {
            LOG.warn("conversation_id={} agenda_pendencia etapa={} falhou: {}", conversationId, etapa, ex.getClass().getSimpleName());
        }
    }
}
