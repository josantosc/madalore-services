package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AvisoFilaPort;

/** Recepção: fila do dia, chegada, chamada, falta e encaixe. Não lida com conteúdo clínico. */
@Service
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class AtendimentoService implements AtendimentoUseCase {

    static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    static final int MOTIVO_MAX = 300;
    static final int ENCAIXE_DURACAO_MIN = 15;
    static final int BUSCA_LIMITE = 10;
    private static final int NOME_MAX = 240;
    private static final int TELEFONE_MAX = 40;

    private final AtendimentoPort atendimentos;
    private final Clock relogio;
    private final HabilitacaoAtendimento habilitacao;
    private final AvisoFilaPort aviso;

    @Autowired
    public AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, AvisoFilaPort aviso) {
        this(atendimentos, habilitacao, Clock.system(ZONA), aviso);
    }

    AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, Clock relogio) {
        this(atendimentos, habilitacao, relogio, AvisoFilaPort.NENHUM);
    }

    AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, Clock relogio,
                       AvisoFilaPort aviso) {
        this.atendimentos = atendimentos;
        this.habilitacao = habilitacao;
        this.relogio = relogio;
        this.aviso = aviso;
    }

    @Override
    public List<Atendimento.FilaItem> fila(String conta, LocalDate data) {
        exigirHabilitada(conta);
        return atendimentos.fila(conta, data == null ? LocalDate.now(relogio) : data);
    }

    @Override
    public Atendimento.Operacao registrarChegada(String conta, long agendamentoId, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        Atendimento.Operacao op = atendimentos.registrarChegada(conta, agendamentoId, ator);
        return avisar(conta, op, "CHEGADA", agendamentoId, op == null ? null : op.id());
    }

    @Override
    public Atendimento.Operacao desfazerChegada(String conta, long atendimentoId, String motivo, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        String texto = motivo == null ? "" : motivo.trim();
        if (texto.isEmpty()) {
            throw new IllegalArgumentException("Informe o motivo para desfazer a chegada");
        }
        if (texto.length() > MOTIVO_MAX) {
            throw new IllegalArgumentException("O motivo deve ter no máximo " + MOTIVO_MAX + " caracteres");
        }
        return avisar(conta, atendimentos.desfazerChegada(conta, atendimentoId, texto, ator), "CHEGADA_DESFEITA",
                null, atendimentoId);
    }

    @Override
    public List<Atendimento.Evento> eventos(String conta, long atendimentoId) {
        exigirHabilitada(conta);
        return atendimentos.eventos(conta, atendimentoId);
    }

    @Override
    public Atendimento.Operacao chamar(String conta, long atendimentoId, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        return avisar(conta, atendimentos.chamar(conta, atendimentoId, null, ator), "CHAMADO", null, atendimentoId);
    }

    @Override
    public Atendimento.Operacao marcarFalta(String conta, long agendamentoId, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        return avisar(conta, atendimentos.marcarFalta(conta, agendamentoId, ator), "FALTOU", agendamentoId, null);
    }

    @Override
    public Atendimento.Operacao desfazerFalta(String conta, long agendamentoId, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        return avisar(conta, atendimentos.desfazerFalta(conta, agendamentoId, ator), "FALTA_DESFEITA", agendamentoId,
                null);
    }

    @Override
    public Atendimento.Operacao encaixe(String conta, Atendimento.Encaixe encaixe, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        if (encaixe == null) {
            throw new IllegalArgumentException("Informe os dados do encaixe");
        }
        if (encaixe.profissionalId() <= 0 || encaixe.especialidadeId() <= 0) {
            throw new IllegalArgumentException("Escolha o profissional e a especialidade");
        }
        String cpf = AgendaService.cpf(encaixe.cpf());
        if (encaixe.nascimento() == null) {
            throw new IllegalArgumentException("Informe a data de nascimento");
        }
        if (encaixe.nascimento().isAfter(LocalDate.now(relogio))) {
            throw new IllegalArgumentException("A data de nascimento não pode ser futura");
        }
        String nome = encaixe.nome() == null ? "" : encaixe.nome().trim();
        if (nome.isEmpty()) {
            throw new IllegalArgumentException("Informe o nome do paciente");
        }
        if (nome.length() > NOME_MAX) {
            throw new IllegalArgumentException("O nome deve ter no máximo " + NOME_MAX + " caracteres");
        }
        String telefone = encaixe.telefone() == null ? "" : encaixe.telefone().trim();
        if (telefone.length() > TELEFONE_MAX) {
            throw new IllegalArgumentException("O telefone deve ter no máximo " + TELEFONE_MAX + " caracteres");
        }
        Atendimento.Encaixe limpo = new Atendimento.Encaixe(encaixe.profissionalId(), encaixe.especialidadeId(),
                encaixe.convenioId(), cpf, encaixe.nascimento(), nome, telefone);
        Atendimento.Operacao op = atendimentos.encaixe(conta, limpo, ENCAIXE_DURACAO_MIN, ator);
        return avisar(conta, op, "ENCAIXE", null, op == null ? null : op.id());
    }

    @Override
    public Atendimento.CadastrosEncaixe cadastrosEncaixe(String conta) {
        exigirHabilitada(conta);
        return atendimentos.cadastrosEncaixe(conta);
    }

    @Override
    public List<Atendimento.PacienteResumo> buscarPacientes(String conta, String termo) {
        exigirHabilitada(conta);
        String texto = termo == null ? "" : termo.trim();
        if (texto.length() < 2) {
            return List.of();
        }
        return atendimentos.buscarPacientes(conta, texto, BUSCA_LIMITE);
    }

    /** O aviso é só para as telas recarregarem; falhar nele não desfaz a operação já gravada. */
    private Atendimento.Operacao avisar(String conta, Atendimento.Operacao op, String tipo, Long agendamentoId,
                                        Long atendimentoId) {
        if (op != null && op.codigo() == CodigoNegocio.SUCESSO) {
            try {
                aviso.publicar(conta, new AvisoFilaPort.Aviso(tipo, agendamentoId, atendimentoId));
            } catch (RuntimeException ignorado) {
                // as telas também recarregam sozinhas a cada minuto
            }
        }
        return op;
    }

    /** Fluxo desligado ou conta fora da agenda do Mandalore: AtendimentoDesabilitadoException (HTTP 404). */
    private void exigirHabilitada(String conta) {
        habilitacao.exigir(conta);
    }

    private static void exigirAtor(String ator) {
        if (ator == null || ator.isBlank()) {
            throw new IllegalArgumentException("Usuário não identificado");
        }
    }
}
