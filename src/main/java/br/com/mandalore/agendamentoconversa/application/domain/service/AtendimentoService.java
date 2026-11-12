package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;

/** Recepção: fila do dia, chegada e desfazer chegada. Não lida com conteúdo clínico. */
@Service
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class AtendimentoService implements AtendimentoUseCase {

    static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    static final int MOTIVO_MAX = 300;

    private final AtendimentoPort atendimentos;
    private final Clock relogio;
    private final HabilitacaoAtendimento habilitacao;

    @Autowired
    public AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao) {
        this(atendimentos, habilitacao, Clock.system(ZONA));
    }

    AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, Clock relogio) {
        this.atendimentos = atendimentos;
        this.habilitacao = habilitacao;
        this.relogio = relogio;
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
        return atendimentos.registrarChegada(conta, agendamentoId, ator);
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
        return atendimentos.desfazerChegada(conta, atendimentoId, texto, ator);
    }

    @Override
    public List<Atendimento.Evento> eventos(String conta, long atendimentoId) {
        exigirHabilitada(conta);
        return atendimentos.eventos(conta, atendimentoId);
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
