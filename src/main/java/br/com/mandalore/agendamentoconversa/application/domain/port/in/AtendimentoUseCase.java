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

    /**
     * Chama o paciente que aguarda, ou o já atendido (ex.: financeiro), para o local informado (opcional). Chamar de
     * novo atualiza horário e local.
     */
    Atendimento.Operacao chamar(String conta, long atendimentoId, String local, String ator);

    /** Locais sugeridos para a chamada (mandalore.clientes.&lt;id&gt;.painel.locais); vazia quando não configurada. */
    List<String> locaisDeChamada(String conta);

    /** Consulta marcada ou confirmada cujo horário já passou. Operacao.id() é o agendamento. */
    Atendimento.Operacao marcarFalta(String conta, long agendamentoId, String ator);

    Atendimento.Operacao desfazerFalta(String conta, long agendamentoId, String ator);

    /** Paciente sem agendamento: cria a consulta de agora e já registra a chegada. Operacao.id() é o atendimento. */
    Atendimento.Operacao encaixe(String conta, Atendimento.Encaixe encaixe, String ator);

    /** Profissionais com especialidades e convênios ativos, para o formulário de encaixe. */
    Atendimento.CadastrosEncaixe cadastrosEncaixe(String conta);

    /** Por nome ou CPF; termo com menos de 2 caracteres devolve vazio. */
    List<Atendimento.PacienteResumo> buscarPacientes(String conta, String termo);

    /** CPF com 11 dígitos (pontuação é ignorada). Mais de um cadastro quando o CPF tem nascimentos diferentes. */
    List<Atendimento.PacienteResumo> pacientesPorCpf(String conta, String cpf);

    /** Cadastro e linha do tempo administrativa, sem conteúdo clínico. Grava acesso_prontuario. */
    Atendimento.FichaPaciente fichaDoPaciente(String conta, long pacienteId, String ator, String ip);

    /**
     * Declaração de comparecimento de paciente que já chegou (atendimento não cancelado). texto aceita marcadores
     * ({paciente}, {chegada}, {saida}...). Operacao.id() é o documento.
     */
    Atendimento.Operacao emitirDeclaracao(String conta, long atendimentoId, String texto, String ator);

    /** HTML da declaração para imprimir. Atestado é do médico: aqui dá "Documento não encontrado". Grava acesso_prontuario. */
    String impressaoDeclaracao(String conta, long documentoId, String ator, String ip);
}
