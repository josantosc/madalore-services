package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.exception.AcessoNegadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort.ProfissionalClinico;

/**
 * Consultório do médico. Regras aqui: posse do profissional, estado do atendimento e validações de liberação.
 * Nunca registra em log texto clínico.
 */
@Service
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class ConsultorioService implements ConsultorioUseCase {

    static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    static final Pattern CID = Pattern.compile("^[A-Z][0-9]{2}(\\.[0-9A-Z]{1,2})?$");
    static final int JUSTIFICATIVA_MAX = 500;
    static final int VALIDADE_PADRAO_DIAS = 30;
    private static final int TEXTO_CLINICO_MAX = 10_000;
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    static final String ACAO_PRONTUARIO = "PRONTUARIO";
    static final String ACAO_HISTORICO = "HISTORICO";
    static final String ACAO_IMPRESSAO = "IMPRESSAO_RECEITA";

    private final AtendimentoPort atendimentos;
    private final Clock relogio;
    private final int validadeDias;
    private final HabilitacaoAtendimento habilitacao;

    @Autowired
    public ConsultorioService(
            AtendimentoPort atendimentos,
            HabilitacaoAtendimento habilitacao,
            @Value("${atendimento.receita.validade-dias:30}") int validadeDias
    ) {
        this(atendimentos, habilitacao, Clock.system(ZONA), validadeDias);
    }

    ConsultorioService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, Clock relogio, int validadeDias) {
        this.atendimentos = atendimentos;
        this.habilitacao = habilitacao;
        this.relogio = relogio;
        this.validadeDias = validadeDias > 0 ? validadeDias : VALIDADE_PADRAO_DIAS;
    }

    // ---- fila e consulta ----

    @Override
    public List<Atendimento.FilaItem> fila(Contexto ctx, LocalDate data) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        return atendimentos.filaDoProfissional(ctx.conta(), prof.id(), data == null ? LocalDate.now(relogio) : data);
    }

    @Override
    public Atendimento.Operacao iniciarConsulta(Contexto ctx, long atendimentoId) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Ficha> ficha = fichaPropria(ctx, prof, atendimentoId);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        if (ficha.get().status() != Atendimento.Status.AGUARDANDO) {
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CONSULTA);
        }
        return atendimentos.iniciarConsulta(ctx.conta(), atendimentoId, prof.id(), ctx.ator());
    }

    @Override
    public Atendimento.Prontuario prontuario(Contexto ctx, long atendimentoId, String ip) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        Atendimento.Ficha ficha = fichaPropria(ctx, prof, atendimentoId)
                .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado"));
        Atendimento.Prontuario prontuario = atendimentos.prontuario(ctx.conta(), atendimentoId)
                .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado"));
        atendimentos.registrarAcesso(ctx.conta(), atendimentoId, ficha.pacienteId(), ACAO_PRONTUARIO, ctx.ator(), ip);
        return prontuario;
    }

    @Override
    public List<Atendimento.Prontuario> historicoDoPaciente(Contexto ctx, long pacienteId, String ip) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        List<Atendimento.Prontuario> proprios = atendimentos.historicoDoPaciente(ctx.conta(), pacienteId).stream()
                .filter(p -> p.atendimento().profissionalId() == prof.id())
                .toList();
        atendimentos.registrarAcesso(ctx.conta(), null, pacienteId, ACAO_HISTORICO, ctx.ator(), ip);
        return proprios;
    }

    // ---- anamnese ----

    @Override
    public Atendimento.Operacao salvarRascunhoAnamnese(Contexto ctx, long atendimentoId, AnamneseInBound in) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        if (in == null) {
            throw new IllegalArgumentException("Informe os dados da anamnese");
        }
        String queixa = clinico(in.queixaPrincipal(), "Queixa principal");
        String historia = clinico(in.historiaDoencaAtual(), "História da doença atual");
        String pessoais = clinico(in.antecedentesPessoais(), "Antecedentes pessoais");
        String familiares = clinico(in.antecedentesFamiliares(), "Antecedentes familiares");
        String medicamentos = clinico(in.medicamentosEmUso(), "Medicamentos em uso");
        String alergias = clinico(in.alergias(), "Alergias");
        String habitos = clinico(in.habitos(), "Hábitos");
        String exame = clinico(in.exameFisico(), "Exame físico");
        Optional<Atendimento.Ficha> ficha = fichaPropria(ctx, prof, atendimentoId);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        if (ficha.get().status() != Atendimento.Status.EM_CONSULTA) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        Atendimento.Anamnese anamnese = new Atendimento.Anamnese(null, atendimentoId, ficha.get().pacienteId(),
                prof.id(), queixa, historia, pessoais, familiares, medicamentos, alergias, habitos, exame,
                agora(), null, true, null, null, null, ctx.ator());
        return atendimentos.salvarRascunhoAnamnese(ctx.conta(), atendimentoId, prof.id(), anamnese, ctx.ator());
    }

    @Override
    public Atendimento.Operacao liberarAnamnese(Contexto ctx, long atendimentoId) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Ficha> ficha = fichaPropria(ctx, prof, atendimentoId);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        if (ficha.get().status() != Atendimento.Status.EM_CONSULTA) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        Atendimento.Anamnese atual = atendimentos.prontuario(ctx.conta(), atendimentoId)
                .map(Atendimento.Prontuario::anamnese)
                .filter(a -> a != null && a.ativa())
                .orElseThrow(() -> new IllegalArgumentException("Não há rascunho de anamnese para liberar"));
        if (atual.liberada()) {
            return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
        }
        if (vazio(atual.queixaPrincipal())) {
            throw new IllegalArgumentException("Informe a queixa principal para liberar a anamnese");
        }
        return atendimentos.liberarAnamnese(ctx.conta(), atendimentoId, prof.id(), ctx.ator());
    }

    @Override
    public Atendimento.Operacao inativarAnamnese(Contexto ctx, long anamneseId, String justificativa) {
        exigirHabilitada(ctx);
        String motivo = justificativa(justificativa);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Anamnese> anamnese = atendimentos.anamnese(ctx.conta(), anamneseId);
        Atendimento.Operacao bloqueio = bloqueioDeInativacao(ctx, prof, anamnese.map(Atendimento.Anamnese::nrAtendimento),
                anamnese.map(a -> a.ativa() ? a.liberada() : null));
        if (bloqueio != null) {
            return bloqueio;
        }
        return atendimentos.inativarAnamnese(ctx.conta(), anamneseId, prof.id(), motivo, ctx.ator());
    }

    // ---- evolução ----

    @Override
    public Atendimento.Operacao criarEvolucao(Contexto ctx, long atendimentoId, EvolucaoInBound in) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        evolucao(in, atendimentoId, 0, prof.id(), ctx.ator()); // valida a entrada antes de consultar o estado
        Optional<Atendimento.Ficha> ficha = fichaPropria(ctx, prof, atendimentoId);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        if (ficha.get().status() != Atendimento.Status.EM_CONSULTA) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        return atendimentos.criarEvolucao(ctx.conta(), atendimentoId, prof.id(),
                evolucao(in, atendimentoId, ficha.get().pacienteId(), prof.id(), ctx.ator()), ctx.ator());
    }

    @Override
    public Atendimento.Operacao editarEvolucao(Contexto ctx, long evolucaoId, EvolucaoInBound in) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Evolucao> atual = atendimentos.evolucao(ctx.conta(), evolucaoId);
        if (atual.isEmpty() || !atual.get().ativa()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        Atendimento.Evolucao e = atual.get();
        Atendimento.Evolucao nova = evolucao(in, e.nrAtendimento(), e.pacienteId(), prof.id(), ctx.ator());
        Atendimento.Operacao bloqueio = bloqueioDeEscrita(ctx, prof, e.nrAtendimento());
        if (bloqueio != null) {
            return bloqueio;
        }
        if (e.liberada()) {
            return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
        }
        return atendimentos.editarEvolucao(ctx.conta(), evolucaoId, prof.id(), nova, ctx.ator());
    }

    @Override
    public Atendimento.Operacao liberarEvolucao(Contexto ctx, long evolucaoId) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Evolucao> atual = atendimentos.evolucao(ctx.conta(), evolucaoId);
        if (atual.isEmpty() || !atual.get().ativa()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        Atendimento.Evolucao e = atual.get();
        Atendimento.Operacao bloqueio = bloqueioDeEscrita(ctx, prof, e.nrAtendimento());
        if (bloqueio != null) {
            return bloqueio;
        }
        if (e.liberada()) {
            return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
        }
        if (vazio(e.texto())) {
            throw new IllegalArgumentException("Escreva a evolução antes de liberar");
        }
        return atendimentos.liberarEvolucao(ctx.conta(), evolucaoId, prof.id(), ctx.ator());
    }

    @Override
    public Atendimento.Operacao inativarEvolucao(Contexto ctx, long evolucaoId, String justificativa) {
        exigirHabilitada(ctx);
        String motivo = justificativa(justificativa);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Evolucao> evolucao = atendimentos.evolucao(ctx.conta(), evolucaoId);
        Atendimento.Operacao bloqueio = bloqueioDeInativacao(ctx, prof, evolucao.map(Atendimento.Evolucao::nrAtendimento),
                evolucao.map(e -> e.ativa() ? e.liberada() : null));
        if (bloqueio != null) {
            return bloqueio;
        }
        return atendimentos.inativarEvolucao(ctx.conta(), evolucaoId, prof.id(), motivo, ctx.ator());
    }

    // ---- diagnóstico ----

    @Override
    public Atendimento.Operacao incluirDiagnostico(Contexto ctx, long atendimentoId, DiagnosticoInBound in) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        if (in == null) {
            throw new IllegalArgumentException("Informe o diagnóstico");
        }
        String cid = in.cid() == null ? "" : in.cid().trim().toUpperCase(Locale.ROOT);
        if (!CID.matcher(cid).matches()) {
            throw new IllegalArgumentException("CID inválido. Use o formato J06 ou J06.9");
        }
        if (in.tipo() == null) {
            throw new IllegalArgumentException("Informe se o diagnóstico é principal ou secundário");
        }
        String descricao = in.descricao() == null ? "" : in.descricao().trim();
        if (descricao.length() > 500) {
            throw new IllegalArgumentException("A descrição do diagnóstico deve ter no máximo 500 caracteres");
        }
        Optional<Atendimento.Ficha> ficha = fichaPropria(ctx, prof, atendimentoId);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        if (ficha.get().status() != Atendimento.Status.EM_CONSULTA) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        Atendimento.Diagnostico diagnostico = new Atendimento.Diagnostico(null, atendimentoId,
                ficha.get().pacienteId(), prof.id(), cid, descricao, in.tipo(), agora(), true, null, null, null,
                ctx.ator());
        return atendimentos.incluirDiagnostico(ctx.conta(), atendimentoId, prof.id(), diagnostico, ctx.ator());
    }

    @Override
    public Atendimento.Operacao inativarDiagnostico(Contexto ctx, long diagnosticoId, String justificativa) {
        exigirHabilitada(ctx);
        String motivo = justificativa(justificativa);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Diagnostico> diagnostico = atendimentos.diagnostico(ctx.conta(), diagnosticoId);
        if (diagnostico.isEmpty() || !diagnostico.get().ativo()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        Atendimento.Operacao bloqueio = bloqueioDeEscrita(ctx, prof, diagnostico.get().nrAtendimento());
        if (bloqueio != null) {
            return bloqueio;
        }
        return atendimentos.inativarDiagnostico(ctx.conta(), diagnosticoId, prof.id(), motivo, ctx.ator());
    }

    // ---- receita ----

    @Override
    public Atendimento.Operacao criarReceita(Contexto ctx, long atendimentoId, ReceitaInBound in) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        receita(in, atendimentoId, 0, prof.id(), ctx.ator()); // valida a entrada antes de consultar o estado
        Optional<Atendimento.Ficha> ficha = fichaPropria(ctx, prof, atendimentoId);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        if (ficha.get().status() != Atendimento.Status.EM_CONSULTA) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        Atendimento.Receita completa = receita(in, atendimentoId, ficha.get().pacienteId(), prof.id(), ctx.ator());
        return atendimentos.criarReceita(ctx.conta(), atendimentoId, prof.id(), completa, ctx.ator());
    }

    @Override
    public Atendimento.Operacao editarReceita(Contexto ctx, long receitaId, ReceitaInBound in) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Receita> atual = atendimentos.receita(ctx.conta(), receitaId);
        if (atual.isEmpty() || !atual.get().ativa()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        Atendimento.Receita r = atual.get();
        Atendimento.Receita nova = receita(in, r.nrAtendimento(), r.pacienteId(), prof.id(), ctx.ator());
        Atendimento.Operacao bloqueio = bloqueioDeEscrita(ctx, prof, r.nrAtendimento());
        if (bloqueio != null) {
            return bloqueio;
        }
        if (r.liberada()) {
            return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
        }
        return atendimentos.editarReceita(ctx.conta(), receitaId, prof.id(), nova, ctx.ator());
    }

    @Override
    public Atendimento.Operacao liberarReceita(Contexto ctx, long receitaId) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Receita> atual = atendimentos.receita(ctx.conta(), receitaId);
        if (atual.isEmpty() || !atual.get().ativa()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        Atendimento.Receita r = atual.get();
        Atendimento.Operacao bloqueio = bloqueioDeEscrita(ctx, prof, r.nrAtendimento());
        if (bloqueio != null) {
            return bloqueio;
        }
        if (r.liberada()) {
            return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
        }
        boolean temItemCompleto = r.itens().stream()
                .anyMatch(i -> !vazio(i.medicamento()) && !vazio(i.posologia()));
        if (!temItemCompleto) {
            throw new IllegalArgumentException("Informe ao menos um medicamento com posologia");
        }
        if (vazio(prof.conselhoNumero()) || vazio(prof.conselhoUf())) {
            return Atendimento.Operacao.falha(CodigoNegocio.PROFISSIONAL_SEM_CONSELHO);
        }
        LocalDate validade = LocalDate.now(relogio).plusDays(validadeDias);
        return atendimentos.liberarReceita(ctx.conta(), receitaId, prof.id(), validade, ctx.ator());
    }

    @Override
    public Atendimento.Operacao cancelarReceita(Contexto ctx, long receitaId, String justificativa) {
        exigirHabilitada(ctx);
        String motivo = justificativa(justificativa);
        ProfissionalClinico prof = profissional(ctx);
        Optional<Atendimento.Receita> receita = atendimentos.receita(ctx.conta(), receitaId);
        Atendimento.Operacao bloqueio = bloqueioDeInativacao(ctx, prof, receita.map(Atendimento.Receita::nrAtendimento),
                receita.map(r -> r.ativa() ? r.liberada() : null));
        if (bloqueio != null) {
            return bloqueio;
        }
        return atendimentos.cancelarReceita(ctx.conta(), receitaId, prof.id(), motivo, ctx.ator());
    }

    @Override
    public Impressao impressaoReceita(Contexto ctx, long receitaId, String ip) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        Atendimento.Receita receita = atendimentos.receita(ctx.conta(), receitaId)
                .orElseThrow(() -> new IllegalArgumentException("Receita não encontrada"));
        fichaPropria(ctx, prof, receita.nrAtendimento())
                .orElseThrow(() -> new IllegalArgumentException("Receita não encontrada"));
        if (!receita.ativa()) {
            throw new IllegalArgumentException("Receita cancelada não pode ser impressa");
        }
        if (!receita.liberada()) {
            throw new IllegalArgumentException("Libere a receita antes de imprimir");
        }
        AtendimentoPort.CabecalhoReceita cabecalho = atendimentos.cabecalhoReceita(ctx.conta(), receitaId)
                .orElseThrow(() -> new IllegalArgumentException("Receita não encontrada"));
        atendimentos.registrarAcesso(ctx.conta(), receita.nrAtendimento(), receita.pacienteId(), ACAO_IMPRESSAO,
                ctx.ator(), ip);
        return new Impressao(html(cabecalho, receita, prof));
    }

    // ---- alta ----

    @Override
    public Atendimento.Operacao finalizar(Contexto ctx, long atendimentoId, FinalizarInBound in) {
        exigirHabilitada(ctx);
        ProfissionalClinico prof = profissional(ctx);
        if (in == null || in.motivoAlta() == null) {
            throw new IllegalArgumentException("Informe o motivo da alta");
        }
        String orientacao = clinico(in.orientacao(), "Orientações");
        Optional<Atendimento.Ficha> ficha = fichaPropria(ctx, prof, atendimentoId);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        if (ficha.get().status() != Atendimento.Status.EM_CONSULTA) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        AtendimentoPort.ContagemClinica contagem = atendimentos.contagemParaFinalizar(ctx.conta(), atendimentoId);
        if (contagem.rascunhos() > 0) {
            return Atendimento.Operacao.falha(CodigoNegocio.RASCUNHO_PENDENTE);
        }
        if (contagem.anamneseEvolucaoLiberadas() < 1) {
            return Atendimento.Operacao.falha(CodigoNegocio.SEM_REGISTRO_CLINICO);
        }
        Atendimento.Alta alta = new Atendimento.Alta(atendimentoId,
                in.desfecho() == null ? Atendimento.Desfecho.ALTA : in.desfecho(),
                orientacao, in.retornoPrevisto(), agora(), ctx.ator());
        return atendimentos.finalizar(ctx.conta(), atendimentoId, prof.id(), in.motivoAlta(), alta, ctx.ator());
    }

    // ---- apoio: posse e estado ----

    /** Fluxo desligado ou conta fora da agenda do Mandalore: AtendimentoDesabilitadoException (HTTP 404). */
    private void exigirHabilitada(Contexto ctx) {
        habilitacao.exigir(ctx.conta());
    }

    private ProfissionalClinico profissional(Contexto ctx) {
        return atendimentos.profissionalDoUsuario(ctx.conta(), ctx.usuarioId())
                .orElseThrow(() -> new AcessoNegadoException("Seu usuário não está vinculado a um profissional"));
    }

    /** Atendimento da conta que pertence ao profissional; de outro profissional lança AcessoNegado. */
    private Optional<Atendimento.Ficha> fichaPropria(Contexto ctx, ProfissionalClinico prof, long nrAtendimento) {
        Optional<Atendimento.Ficha> ficha = atendimentos.atendimento(ctx.conta(), nrAtendimento);
        if (ficha.isPresent() && ficha.get().profissionalId() != prof.id()) {
            throw new AcessoNegadoException("Este atendimento é de outro profissional");
        }
        return ficha;
    }

    /** Devolve a falha de negócio (ou null se pode gravar): atendimento existe, é do profissional e está EM_CONSULTA. */
    private Atendimento.Operacao bloqueioDeEscrita(Contexto ctx, ProfissionalClinico prof, long nrAtendimento) {
        Optional<Atendimento.Ficha> ficha = fichaPropria(ctx, prof, nrAtendimento);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        if (ficha.get().status() != Atendimento.Status.EM_CONSULTA) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        return null;
    }

    /**
     * Inativação/cancelamento: o registro precisa existir e estar ativo (liberada != null), o atendimento deve estar
     * EM_CONSULTA e o registro precisa estar liberado (rascunho se descarta, não se inativa).
     */
    private Atendimento.Operacao bloqueioDeInativacao(Contexto ctx, ProfissionalClinico prof,
                                                     Optional<Long> nrAtendimento, Optional<Boolean> liberada) {
        if (nrAtendimento.isEmpty() || liberada.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        Atendimento.Operacao bloqueio = bloqueioDeEscrita(ctx, prof, nrAtendimento.get());
        if (bloqueio != null) {
            return bloqueio;
        }
        if (!liberada.get()) {
            return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_NAO_LIBERADO);
        }
        return null;
    }

    // ---- apoio: validação e montagem ----

    private Atendimento.Evolucao evolucao(EvolucaoInBound in, long nrAtendimento, long pacienteId, long profissionalId,
                                          String ator) {
        if (in == null) {
            throw new IllegalArgumentException("Informe a evolução");
        }
        if (in.pesoKg() != null && (in.pesoKg().signum() <= 0 || in.pesoKg().compareTo(new BigDecimal("1000")) >= 0)) {
            throw new IllegalArgumentException("Peso inválido");
        }
        if (in.alturaCm() != null && (in.alturaCm().signum() <= 0 || in.alturaCm().compareTo(new BigDecimal("300")) > 0)) {
            throw new IllegalArgumentException("Altura inválida");
        }
        return new Atendimento.Evolucao(null, nrAtendimento, pacienteId, profissionalId,
                clinico(in.texto(), "Evolução"), in.pesoKg(), in.alturaCm(), in.retornoPrevisto(), agora(), null, true,
                null, null, null, ator);
    }

    private Atendimento.Receita receita(ReceitaInBound in, long nrAtendimento, long pacienteId, long profissionalId,
                                        String ator) {
        if (in == null) {
            throw new IllegalArgumentException("Informe a receita");
        }
        String observacao = in.observacao() == null ? "" : in.observacao().trim();
        if (observacao.length() > 1000) {
            throw new IllegalArgumentException("A observação da receita deve ter no máximo 1000 caracteres");
        }
        List<Atendimento.ReceitaItem> itens = new ArrayList<>();
        if (in.itens() != null) {
            for (Atendimento.ReceitaItem item : in.itens()) {
                if (item == null) {
                    throw new IllegalArgumentException("Item de receita inválido");
                }
                itens.add(new Atendimento.ReceitaItem(itens.size() + 1,
                        limitado(item.medicamento(), 300, "Medicamento"),
                        limitado(item.apresentacao(), 200, "Apresentação"),
                        limitado(item.posologia(), 500, "Posologia"),
                        limitado(item.via(), 60, "Via"),
                        limitado(item.quantidade(), 100, "Quantidade"),
                        item.usoContinuo(),
                        limitado(item.observacao(), 500, "Observação do item")));
            }
        }
        return new Atendimento.Receita(null, nrAtendimento, pacienteId, profissionalId,
                in.tipo() == null ? Atendimento.TipoReceita.COMUM : in.tipo(), observacao, agora(), null, null, true,
                null, null, null, ator, itens);
    }

    private static String justificativa(String valor) {
        String texto = valor == null ? "" : valor.trim();
        if (texto.isEmpty()) {
            throw new IllegalArgumentException("Informe a justificativa");
        }
        if (texto.length() > JUSTIFICATIVA_MAX) {
            throw new IllegalArgumentException("A justificativa deve ter no máximo " + JUSTIFICATIVA_MAX + " caracteres");
        }
        return texto;
    }

    private static String clinico(String valor, String campo) {
        return limitado(valor, TEXTO_CLINICO_MAX, campo);
    }

    /** A mensagem de erro cita só o nome do campo, nunca o conteúdo. */
    private static String limitado(String valor, int max, String campo) {
        String texto = valor == null ? "" : valor.trim();
        if (texto.length() > max) {
            throw new IllegalArgumentException(campo + " deve ter no máximo " + max + " caracteres");
        }
        return texto;
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }

    private LocalDateTime agora() {
        return LocalDateTime.now(relogio);
    }

    // ---- impressão ----

    private static String html(AtendimentoPort.CabecalhoReceita cabecalho, Atendimento.Receita receita,
                               ProfissionalClinico prof) {
        StringBuilder h = new StringBuilder(2048);
        h.append("<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"UTF-8\">")
                .append("<title>Receita</title>")
                .append("<style>body{font-family:sans-serif;margin:2rem;color:#111}")
                .append("h1{font-size:1.3rem;margin:0}.meta{margin:.5rem 0 1rem}")
                .append("ol{padding-left:1.2rem}li{margin-bottom:.8rem}")
                .append(".assinatura{margin-top:3rem;border-top:1px solid #111;width:18rem;padding-top:.3rem}")
                .append("</style></head><body>");
        h.append("<h1>").append(esc(cabecalho.clinica())).append("</h1>");
        h.append("<div class=\"meta\">Paciente: ").append(esc(cabecalho.paciente()));
        if (!vazio(cabecalho.cpf())) {
            h.append(" &middot; CPF: ").append(esc(cabecalho.cpf()));
        }
        h.append("<br>Data: ").append(esc(receita.dtLiberacao().toLocalDate().format(DATA)));
        if (receita.dtValidade() != null) {
            h.append(" &middot; Válida até: ").append(esc(receita.dtValidade().format(DATA)));
        }
        h.append("</div><ol>");
        for (Atendimento.ReceitaItem item : receita.itens()) {
            h.append("<li><strong>").append(esc(item.medicamento())).append("</strong>");
            if (!vazio(item.apresentacao())) {
                h.append(" ").append(esc(item.apresentacao()));
            }
            h.append("<br>").append(esc(item.posologia()));
            if (!vazio(item.via())) {
                h.append("<br>Via: ").append(esc(item.via()));
            }
            if (!vazio(item.quantidade())) {
                h.append("<br>Quantidade: ").append(esc(item.quantidade()));
            }
            if (item.usoContinuo()) {
                h.append("<br>Uso contínuo");
            }
            if (!vazio(item.observacao())) {
                h.append("<br>").append(esc(item.observacao()));
            }
            h.append("</li>");
        }
        h.append("</ol>");
        if (!vazio(receita.observacao())) {
            h.append("<p>").append(esc(receita.observacao())).append("</p>");
        }
        h.append("<div class=\"assinatura\">").append(esc(prof.nome())).append("<br>")
                .append(esc(prof.conselhoNumero())).append("/").append(esc(prof.conselhoUf()))
                .append("</div></body></html>");
        return h.toString();
    }

    static String esc(String valor) {
        if (valor == null) {
            return "";
        }
        StringBuilder s = new StringBuilder(valor.length() + 16);
        for (char c : valor.toCharArray()) {
            switch (c) {
                case '&' -> s.append("&amp;");
                case '<' -> s.append("&lt;");
                case '>' -> s.append("&gt;");
                case '"' -> s.append("&quot;");
                case '\'' -> s.append("&#39;");
                default -> s.append(c);
            }
        }
        return s.toString();
    }
}
