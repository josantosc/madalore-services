package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.FeriadosNacionais;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil.Etapa;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.FunilPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.RegrasFunilPort;

@Service
@ConditionalOnProperty(name = {"funil.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class FunilService implements FunilUseCase {

    static final String SISTEMA = "mandalore";
    static final BigDecimal VALOR_MAXIMO = new BigDecimal("999999.99");

    private static final Logger LOG = LoggerFactory.getLogger(FunilService.class);
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private static final Duration HISTORICO = Duration.ofDays(30);
    private static final DateTimeFormatter DIA_CURTO = DateTimeFormatter.ofPattern("dd/MM");

    private final FunilPort funil;
    private final ChatwootPort chatwoot;
    private final RegrasFunilPort regras;
    private final Clock relogio;
    private final int diasSemResposta;
    private final String url;

    @Autowired
    public FunilService(
            FunilPort funil,
            ChatwootPort chatwoot,
            RegrasFunilPort regras,
            @Value("${funil.dias-sem-resposta:30}") int diasSemResposta,
            @Value("${funil.url:}") String url
    ) {
        this(funil, chatwoot, regras, Clock.system(ZONA), diasSemResposta, url);
    }

    FunilService(FunilPort funil, ChatwootPort chatwoot, RegrasFunilPort regras, Clock relogio, int diasSemResposta,
                 String url) {
        this.funil = funil;
        this.chatwoot = chatwoot;
        this.regras = regras;
        this.relogio = relogio;
        this.diasSemResposta = diasSemResposta;
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    @Override
    public void abrirOrcamento(String conta, String conversa, Funil.Origem origem, BigDecimal valor, Integer qtdItens) {
        long id = funil.abrir(conta, conversa, origem, valor, qtdItens, hoje(), SISTEMA);
        LOG.info("conversation_id={} funil oportunidade={} origem={}", conversa, id, origem);
        etiquetar(conta, conversa);
    }

    @Override
    public void orcamentoCalculado(String conta, String conversa, BigDecimal valor, int qtdItens, String atendente) {
        long id = funil.abrir(conta, conversa, Funil.Origem.ORCAMENTO_MANUAL, null, null, hoje(), SISTEMA);
        String usuario = texto(atendente) == null ? "chatwoot" : texto(atendente);
        funil.valor(id, valor.min(VALOR_MAXIMO).setScale(2, RoundingMode.HALF_UP), qtdItens, usuario);
        LOG.info("conversation_id={} funil oportunidade={} origem=COMANDO exames={}", conversa, id, qtdItens);
        etiquetar(conta, conversa);
    }

    private void etiquetar(String conta, String conversa) {
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

    /**
     * A primeira mensagem envia o orçamento e agenda o toque 1. Depois, a mensagem do dia do toque (ou de um
     * atrasado) cumpre o toque e agenda o próximo; a que chega antes do vencimento não mexe na cadência.
     */
    private void atendenteRespondeu(Funil.Oportunidade o, String atendente) {
        String usuario = atendente == null || atendente.isBlank() ? "chatwoot" : atendente;
        Funil.Regras r = regras.regras(o.conta());
        if (o.etapa() == Etapa.NOVO) {
            funil.mudar(o.id(), Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null, r.diasUteis(hoje(), r.diasAte(1)), 1,
                    atendente, usuario);
            return;
        }
        if (!o.etapa().aberta() || o.encerraEm() != null || o.proximaAcao() == null || hoje().isBefore(o.proximaAcao())) {
            return;
        }
        int cumprido = Math.max(o.toque(), 1);
        if (cumprido < r.toques()) {
            funil.avancar(o.id(), cumprido + 1, r.diasUteis(hoje(), r.diasAte(cumprido + 1)), null, usuario);
        } else {
            funil.avancar(o.id(), cumprido, null, r.diasUteis(hoje(), r.diasEncerramento()), usuario);
        }
    }

    /** Resposta do paciente depois do orçamento: Em negociação e a cadência recomeça do toque 1. */
    private void pacienteRespondeu(Funil.Oportunidade o) {
        funil.respondeu(o.id());
        Funil.Regras r = regras.regras(o.conta());
        LocalDate toque1 = r.diasUteis(hoje(), r.diasAte(1));
        if (o.etapa() == Etapa.ORCAMENTO_ENVIADO) {
            funil.mudar(o.id(), Etapa.ORCAMENTO_ENVIADO, Etapa.EM_NEGOCIACAO, null, toque1, 1, null, SISTEMA);
        } else if (o.etapa() == Etapa.EM_NEGOCIACAO) {
            if (o.toque() != 1 || o.encerraEm() != null) {
                funil.avancar(o.id(), 1, toque1, null, SISTEMA);
            } else if (!toque1.equals(o.proximaAcao())) {
                funil.proximaAcao(o.id(), toque1, SISTEMA);
            }
        }
    }

    @Override
    public Painel painel(Set<String> contas, Set<String> contasConhecidas, boolean podeEditarRegras) {
        List<Funil.Oportunidade> oportunidades = funil.listar(contas, relogio.instant().minus(HISTORICO));
        Map<String, Funil.RegrasSalvas> salvas = regras.salvas();
        Set<String> visiveis = new TreeSet<>(contas);
        if (contas.isEmpty()) {
            contasConhecidas.stream().filter(Objects::nonNull).forEach(visiveis::add);
            visiveis.addAll(salvas.keySet());
            oportunidades.forEach(o -> visiveis.add(o.conta()));
        }
        List<RegrasDaConta> lista = new ArrayList<>();
        visiveis.forEach(conta -> {
            Funil.RegrasSalvas salva = salvas.get(conta);
            lista.add(salva == null ? new RegrasDaConta(conta, regras.padrao(), false, null, null)
                    : new RegrasDaConta(conta, salva.regras(), true, salva.atualizadaPor(), salva.atualizadaEm()));
        });
        if (lista.isEmpty()) {
            lista.add(new RegrasDaConta(null, regras.padrao(), false, null, null));
        }
        LocalDate hoje = hoje();
        return new Painel(hoje, oportunidades, lista, diasSemResposta, FeriadosNacionais.entre(hoje, hoje.plusYears(1)),
                podeEditarRegras);
    }

    @Override
    public void salvarRegras(String conta, Funil.Regras novas, String usuario) {
        if (conta == null || conta.isBlank()) {
            throw new IllegalArgumentException("Escolha a clínica");
        }
        regras.salvar(conta.trim(), novas, usuario);
        LOG.info("funil regras conta={} contatos={} encerramento={} usuario={}", conta, novas.cadencia(),
                novas.diasEncerramento(), usuario);
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
        Integer toque = !etapa.aberta() ? null
                : etapa == Etapa.NOVO ? Integer.valueOf(0)
                : !atual.etapa().aberta() || atual.toque() < 1 ? Integer.valueOf(1) : null;
        if (!funil.mudar(id, atual.etapa(), etapa, motivoFinal, proxima, toque, null, usuario)) {
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
    public void definirValor(long id, BigDecimal valor, String usuario) {
        if (valor != null && (valor.signum() < 0 || valor.compareTo(VALOR_MAXIMO) > 0)) {
            throw new IllegalArgumentException("Informe um valor entre R$ 0,00 e R$ 999.999,99");
        }
        BigDecimal centavos = valor == null ? null : valor.setScale(2, RoundingMode.HALF_UP);
        if (!funil.valor(id, centavos, null, usuario)) {
            throw new IllegalArgumentException("Oportunidade não encontrada");
        }
    }

    @Override
    public void fecharAutomaticas() {
        int agendadas = funil.fecharAgendadasPelaTrilha();
        int encerradas = funil.fecharEncerradas(hoje());
        int semResposta = funil.fecharSemResposta(relogio.instant().minus(Duration.ofDays(diasSemResposta)));
        if (agendadas + encerradas + semResposta > 0) {
            LOG.info("funil fechamento agendadas={} encerradas={} sem_resposta={}", agendadas, encerradas, semResposta);
        }
    }

    /** Uma nota por vencimento: o atrasado fica na tela e no resumo diário, sem repetir nota na conversa. */
    @Override
    public int avisarRetornos() {
        LocalDate hoje = hoje();
        List<Funil.Oportunidade> pendentes = funil.retornosPendentes(hoje);
        int avisados = 0;
        for (Funil.Oportunidade o : pendentes) {
            try {
                chatwoot.notaPrivada(o.conta(), o.conversa(), notaRetorno(o, regras.regras(o.conta()), hoje));
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

    String notaRetorno(Funil.Oportunidade o, Funil.Regras r, LocalDate hoje) {
        StringBuilder texto = new StringBuilder("Retorno do orçamento: ").append(switch (o.etapa()) {
            case NOVO -> "o orçamento ainda não foi enviado ao paciente";
            case ORCAMENTO_ENVIADO -> "orçamento enviado, sem resposta do paciente";
            default -> "em negociação";
        }).append(".\n");
        if (o.etapa() != Etapa.NOVO) {
            int toque = Math.min(Math.max(o.toque(), 1), r.toques());
            texto.append("Contato ").append(toque).append(" de ").append(r.toques()).append(": ")
                    .append(objetivo(toque, r.toques())).append('\n');
            if (o.proximaAcao() != null && o.proximaAcao().isBefore(hoje)) {
                texto.append("Atrasado: vencia em ").append(DIA_CURTO.format(o.proximaAcao())).append(".\n");
            }
        }
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

    /** O primeiro contato tira dúvidas, o último encerra com a porta aberta e os do meio oferecem horário. */
    static String objetivo(int toque, int toques) {
        if (toque >= toques) {
            return "avise que vai encerrar o atendimento por enquanto e que é só chamar quando quiser agendar.";
        }
        if (toque == 1) {
            return "pergunte se ficou alguma dúvida sobre os exames, o preparo ou o valor.";
        }
        return "ofereça dois horários para agendar.";
    }

    private LocalDate hoje() {
        return LocalDate.now(relogio);
    }

    private static void tentar(Funil.Oportunidade o, String etapa, Runnable acao) {
        try {
            acao.run();
        } catch (RuntimeException ex) {
            LOG.warn("conversation_id={} funil retorno etapa={} falhou: {}", o.conversa(), etapa, ex.getClass().getSimpleName());
        }
    }
}
