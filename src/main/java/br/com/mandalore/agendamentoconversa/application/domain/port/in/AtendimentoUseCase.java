package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.time.LocalDate;
import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;

/** Tela /atendimento da recepção: fila do dia e chegada. Nunca expõe conteúdo clínico. */
public interface AtendimentoUseCase {

    /** Agendamentos do dia com a situação de chegada e o tempo de espera. */
    List<Atendimento.FilaItem> fila(String conta, LocalDate data);

    /** Últimas chamadas de hoje para o painel público da sala de espera, com o nome do paciente abreviado. */
    List<Atendimento.Chamada> chamadasDoPainel(String conta);

    /** Operacao.id() é o número do atendimento. Chegada repetida devolve o atendimento existente. */
    Atendimento.Operacao registrarChegada(String conta, long agendamentoId, String ator);

    /** Só enquanto o atendimento está AGUARDANDO. Motivo obrigatório. */
    Atendimento.Operacao desfazerChegada(String conta, long atendimentoId, String motivo, String ator);

    /** Histórico do atendimento (tipo, usuário, detalhe curto). */
    List<Atendimento.Evento> eventos(String conta, long atendimentoId);

    /** Chama o paciente que aguarda. Chamar de novo atualiza o horário da chamada. */
    Atendimento.Operacao chamar(String conta, long atendimentoId, String ator);

    /** Consulta marcada ou confirmada cujo horário já passou. Operacao.id() é o agendamento. */
    Atendimento.Operacao marcarFalta(String conta, long agendamentoId, String ator);

    Atendimento.Operacao desfazerFalta(String conta, long agendamentoId, String ator);

    /** Paciente sem agendamento: cria a consulta de agora e já registra a chegada. Operacao.id() é o atendimento. */
    Atendimento.Operacao encaixe(String conta, Atendimento.Encaixe encaixe, String ator);

    /** Profissionais com especialidades e convênios ativos, para o formulário de encaixe. */
    Atendimento.CadastrosEncaixe cadastrosEncaixe(String conta);

    /** Por nome ou CPF; termo com menos de 2 caracteres devolve vazio. */
    List<Atendimento.PacienteResumo> buscarPacientes(String conta, String termo);
}
