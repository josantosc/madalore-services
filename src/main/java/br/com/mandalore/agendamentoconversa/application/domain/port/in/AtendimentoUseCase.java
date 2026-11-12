package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.time.LocalDate;
import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;

/** Tela /atendimento da recepção: fila do dia e chegada. Nunca expõe conteúdo clínico. */
public interface AtendimentoUseCase {

    /** Agendamentos do dia com a situação de chegada e o tempo de espera. */
    List<Atendimento.FilaItem> fila(String conta, LocalDate data);

    /** Operacao.id() é o número do atendimento. Chegada repetida devolve o atendimento existente. */
    Atendimento.Operacao registrarChegada(String conta, long agendamentoId, String ator);

    /** Só enquanto o atendimento está AGUARDANDO. Motivo obrigatório. */
    Atendimento.Operacao desfazerChegada(String conta, long atendimentoId, String motivo, String ator);

    /** Histórico do atendimento (tipo, usuário, detalhe curto). */
    List<Atendimento.Evento> eventos(String conta, long atendimentoId);
}
