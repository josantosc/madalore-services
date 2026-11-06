package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil.Etapa;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.FunilPort;

@Service
@ConditionalOnProperty(name = {"funil.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class FunilService implements FunilUseCase {

    static final String SISTEMA = "mandalore";
    static final int DIAS_ORCAMENTO_ENVIADO = 2;
    static final int DIAS_EM_NEGOCIACAO = 3;

    private static final Logger LOG = LoggerFactory.getLogger(FunilService.class);
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private static final Duration HISTORICO = Duration.ofDays(30);

    private final FunilPort funil;
    private final ChatwootPort chatwoot;
    private final Clock relogio;
    private final int diasSemResposta;
    private final String url;

    @Autowired
    public FunilService(
            FunilPort funil,
            ChatwootPort chatwoot,
            @Value("${funil.dias-sem-resposta:15}") int diasSemResposta,
            @Value("${funil.url:}") String url
    ) {
        this(funil, chatwoot, Clock.system(ZONA), diasSemResposta, url);
    }

    FunilService(FunilPort funil, ChatwootPort chatwoot, Clock relogio, int diasSemResposta, String url) {
        this.funil = funil;
        this.chatwoot = chatwoot;
        this.relogio = relogio;
        this.diasSemResposta = diasSemResposta;
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    @Override
    public void abrirOrcamento(String conta, String conversa, BigDecimal valor, Integer qtdItens) {
        long id = funil.abrir(conta, conversa, Funil.Origem.ORCAMENTO_FOTO, valor, qtdItens, hoje(), SISTEMA);
        LOG.info("conversation_id={} funil oportunidade={} origem=ORCAMENTO_FOTO", conversa, id);
        try {
            chatwoot.etiqueta(conta, conversa, Funil.ETIQUETA_ORCAMENTO, true);
        } catch (RuntimeException ex) {
            LOG.warn("conversation_id={} funil etiqueta {} falhou: {}", conversa, Funil.ETIQUETA_ORCAMENTO,
                    ex.getClass().getSimpleName());
        }
    }

    @Override
    public void registrar(Funil.Evento evento) {
        Optional<Funil.Oportunidade> aberta = funil.aberta(evento.conta(), evento.conversa());
        switch (evento.tipo()) {
            case CONVERSA -> {
                if (aberta.isEmpty() && evento.etiquetas().contains(Funil.ETIQUETA_ORCAMENTO)) {
                    long id = funil.abrir(evento.conta(), evento.conversa(), Funil.Origem.ORCAMENTO_MANUAL,
                            null, null, hoje(), SISTEMA);
                    LOG.info("conversation_id={} funil oportunidade={} origem=ORCAMENTO_MANUAL", evento.conversa(), id);
                    contato(id, null, null, evento);
                }
            }
            case MENSAGEM_ATENDENTE -> aberta.ifPresent(o -> atendenteRespondeu(o, evento.atendente()));
            case MENSAGEM_PACIENTE -> aberta.ifPresent(this::pacienteRespondeu);
        }
        aberta.ifPresent(o -> contato(o.id(), o.paciente(), o.responsavel(), evento));
    }

    /** Responsável: o atendente atribuído; sem atribuição, quem respondeu por último. */
    private void contato(long id, String pacienteAtual, String responsavelAtual, Funil.Evento evento) {
        String paciente = evento.paciente();
        String responsavel = evento.atribuido() != null ? evento.atribuido()
                : evento.tipo() == Funil.Evento.Tipo.MENSAGEM_ATENDENTE ? texto(evento.atendente()) : null;
        if (Objects.equals(paciente, pacienteAtual)) {
            paciente = null;
        }
        if (Objects.equals(responsavel, responsavelAtual)) {
            responsavel = null;
        }
        if (paciente != null || responsavel != null) {
            funil.contato(id, paciente, responsavel);
        }
    }

    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    private void atendenteRespondeu(Funil.Oportunidade oportunidade, String atendente) {
        String usuario = atendente == null || atendente.isBlank() ? "chatwoot" : atendente;
        switch (oportunidade.etapa()) {
            case NOVO -> funil.mudar(oportunidade.id(), Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null,
                    diasUteis(DIAS_ORCAMENTO_ENVIADO), atendente, usuario);
            case ORCAMENTO_ENVIADO -> funil.proximaAcao(oportunidade.id(), diasUteis(DIAS_ORCAMENTO_ENVIADO), usuario);
            case EM_NEGOCIACAO -> funil.proximaAcao(oportunidade.id(), diasUteis(DIAS_EM_NEGOCIACAO), usuario);
            default -> {
            }
        }
    }

    private void pacienteRespondeu(Funil.Oportunidade oportunidade) {
        funil.respondeu(oportunidade.id());
        if (oportunidade.etapa() == Etapa.ORCAMENTO_ENVIADO) {
            funil.mudar(oportunidade.id(), Etapa.ORCAMENTO_ENVIADO, Etapa.EM_NEGOCIACAO, null, diasUteis(1), null, SISTEMA);
        }
    }

    @Override
    public Painel painel(Set<String> contas) {
        return new Painel(hoje(), funil.listar(contas, relogio.instant().minus(HISTORICO)));
    }

    @Override
    public Funil.Oportunidade buscar(long id) {
        return funil.buscar(id).orElseThrow(() -> new IllegalArgumentException("Oportunidade não encontrada"));
    }

    @Override
    public void mudarEtapa(long id, Etapa etapa, Funil.MotivoPerda motivo, String usuario) {
        if (etapa == null) {
            throw new IllegalArgumentException("Informe a etapa");
        }
        if (etapa == Etapa.PERDIDO && motivo == null) {
            throw new IllegalArgumentException("Informe o motivo da perda");
        }
        Funil.Oportunidade atual = buscar(id);
        Funil.MotivoPerda motivoFinal = etapa == Etapa.PERDIDO ? motivo : null;
        if (atual.etapa() == etapa && atual.motivoPerda() == motivoFinal) {
            return;
        }
        LocalDate proxima = !etapa.aberta() ? null
                : atual.etapa().aberta() && atual.proximaAcao() != null ? atual.proximaAcao() : hoje();
        if (!funil.mudar(id, atual.etapa(), etapa, motivoFinal, proxima, null, usuario)) {
            throw new IllegalStateException("A oportunidade mudou enquanto você editava. Atualize a tela.");
        }
        LOG.info("funil oportunidade={} etapa {} -> {} usuario={}", id, atual.etapa(), etapa, usuario);
    }

    @Override
    public void adiarRetorno(long id, LocalDate data, String usuario) {
        if (data == null || data.isBefore(hoje())) {
            throw new IllegalArgumentException("Escolha hoje ou uma data futura para o retorno");
        }
        if (!buscar(id).etapa().aberta()) {
            throw new IllegalArgumentException("Oportunidade fechada não tem retorno");
        }
        funil.proximaAcao(id, data, usuario);
    }

    @Override
    public void fecharAutomaticas() {
        int agendadas = funil.fecharAgendadasPelaTrilha();
        int semResposta = funil.fecharSemResposta(relogio.instant().minus(Duration.ofDays(diasSemResposta)));
        if (agendadas + semResposta > 0) {
            LOG.info("funil fechamento agendadas={} sem_resposta={}", agendadas, semResposta);
        }
    }

    @Override
    public int avisarRetornos() {
        LocalDate hoje = hoje();
        List<Funil.Oportunidade> pendentes = funil.retornosPendentes(hoje);
        int avisados = 0;
        for (Funil.Oportunidade o : pendentes) {
            try {
                chatwoot.notaPrivada(o.conta(), o.conversa(), notaRetorno(o, hoje));
                tentar(o, "equipe", () -> chatwoot.atribuirEquipe(o.conta(), o.conversa(), Setor.FATURAMENTO));
                tentar(o, "abrir", () -> chatwoot.abrirAtendente(o.conta(), o.conversa()));
                funil.retornoAvisado(o.id(), hoje);
                avisados++;
            } catch (RuntimeException ex) {
                LOG.warn("conversation_id={} funil retorno oportunidade={} falhou: {}",
                        o.conversa(), o.id(), ex.getClass().getSimpleName());
            }
        }
        LOG.info("funil retornos pendentes={} avisados={}", pendentes.size(), avisados);
        return avisados;
    }

    String notaRetorno(Funil.Oportunidade o, LocalDate hoje) {
        StringBuilder texto = new StringBuilder("Retorno do orçamento: ").append(switch (o.etapa()) {
            case NOVO -> "o orçamento ainda não foi enviado ao paciente";
            case ORCAMENTO_ENVIADO -> "orçamento enviado, sem resposta do paciente";
            default -> "em negociação";
        }).append(".\n");
        if (o.valor() != null) {
            texto.append("Valor: ").append(NotaOrcamento.reais(o.valor()));
            if (o.qtdItens() != null) {
                texto.append(" (").append(o.qtdItens()).append(o.qtdItens() == 1 ? " exame)" : " exames)");
            }
            texto.append(".\n");
        }
        if (o.ultimaRespostaEm() != null) {
            long dias = ChronoUnit.DAYS.between(o.ultimaRespostaEm().atZone(ZONA).toLocalDate(), hoje);
            if (dias > 0) {
                texto.append("Última mensagem do paciente há ").append(dias).append(dias == 1 ? " dia" : " dias").append(".\n");
            }
        }
        texto.append("Até 24 horas depois da última mensagem do paciente dá para escrever aqui. Depois disso, ligue.");
        if (!url.isBlank()) {
            texto.append("\nQuando fechar, marque Agendado ou Perdido no funil: ").append(url).append("/funil");
        }
        return texto.toString();
    }

    private LocalDate hoje() {
        return LocalDate.now(relogio);
    }

    LocalDate diasUteis(int dias) {
        LocalDate data = hoje();
        int restantes = dias;
        while (restantes > 0) {
            data = data.plusDays(1);
            if (data.getDayOfWeek() != DayOfWeek.SATURDAY && data.getDayOfWeek() != DayOfWeek.SUNDAY) {
                restantes--;
            }
        }
        return data;
    }

    private static void tentar(Funil.Oportunidade o, String etapa, Runnable acao) {
        try {
            acao.run();
        } catch (RuntimeException ex) {
            LOG.warn("conversation_id={} funil retorno etapa={} falhou: {}", o.conversa(), etapa, ex.getClass().getSimpleName());
        }
    }
}
