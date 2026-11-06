package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

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

    /**
     * A recepção fez no sistema da clínica: nota com quem fez e o código, etiqueta acompanhando as abertas e, sem
     * nenhuma aberta, a conversa resolvida se ainda estiver com o bot.
     */
    public void concluida(String conta, long pendenciaId) {
        Agenda.Pendencia pendencia = agenda.pendencia(conta, pendenciaId).orElse(null);
        if (pendencia == null || !vinculada(pendencia.conversaId())) {
            return;
        }
        String conversa = pendencia.conversaId();
        executor.execute(() -> {
            tentar(conversa, "nota", () -> chatwoot.notaPrivada(conta, conversa, notaConcluida(pendencia)));
            boolean abertas = agenda.pendenciasAbertasDaConversa(conta, conversa) > 0;
            tentar(conversa, "etiqueta", () -> chatwoot.etiqueta(conta, conversa, ETIQUETA, abertas));
            if (!abertas) {
                tentar(conversa, "resolver", () -> chatwoot.resolverSePendente(conta, conversa));
            }
        });
    }

    String notaConcluida(Agenda.Pendencia pendencia) {
        StringBuilder texto = new StringBuilder("Feito no sistema da clínica: ").append(pendencia.tipo().titulo());
        if (pendencia.paciente() != null && !pendencia.paciente().isBlank()) {
            texto.append("\nPaciente: ").append(pendencia.paciente());
        }
        if (pendencia.inicio() != null) {
            texto.append("\nConsulta: ").append(TextoPendencia.dataHora(pendencia.inicio()));
        }
        if (pendencia.codigoExterno() != null && !pendencia.codigoExterno().isBlank()) {
            texto.append("\nCódigo no sistema da clínica: ").append(pendencia.codigoExterno());
        }
        texto.append("\nPor: ").append(pendencia.concluidaPor() == null ? "recepção" : pendencia.concluidaPor());
        if (pendencia.concluidaEm() != null) {
            texto.append(" em ").append(TextoPendencia.dataHora(LocalDateTime.ofInstant(pendencia.concluidaEm(), ZONA)));
        }
        if (pendencia.observacao() != null && !pendencia.observacao().isBlank()) {
            texto.append("\nObservação: ").append(pendencia.observacao());
        }
        return texto.toString();
    }

    /** Depois de reabrir ou descartar: a etiqueta acompanha as pendências abertas da conversa. */
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
