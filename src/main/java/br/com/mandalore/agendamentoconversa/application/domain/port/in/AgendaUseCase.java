package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;

/** Tela /agenda da recepção. Todo método recebe a conta (clínica) já autorizada para o usuário. */
public interface AgendaUseCase {

    /** Consulta marcada pela recepção. codigoExterno preenchido: já lançada no sistema da clínica. */
    record NovaConsulta(
            long profissionalId,
            long especialidadeId,
            Long convenioId,
            LocalDateTime inicio,
            int duracaoMin,
            String local,
            String cpf,
            LocalDate nascimento,
            String nome,
            String telefone,
            String codigoExterno
    ) {
    }

    record NovoHorario(long profissionalId, long especialidadeId, Long convenioId, LocalDateTime inicio, int duracaoMin,
                       String local, String codigoExterno) {
    }

    record Dia(LocalDate dia, List<Agenda.LinhaDia> linhas, List<Agenda.ResumoDia> semana) {
    }

    record Detalhe(Agenda.Agendamento agendamento, List<Agenda.Evento> historico, List<Agenda.Pendencia> pendencias) {
    }

    record HistoricoPaciente(Agenda.Paciente paciente, List<Agenda.Agendamento> agendamentos) {
    }

    List<String> contas();

    Dia dia(String conta, LocalDate dia);

    Detalhe detalhe(String conta, long agendamentoId);

    Agenda.Operacao marcar(String conta, NovaConsulta consulta, String usuario);

    Agenda.Operacao confirmar(String conta, long agendamentoId, boolean jaFeitoNoSistema, String usuario);

    Agenda.Operacao cancelar(String conta, long agendamentoId, boolean jaFeitoNoSistema, String usuario);

    Agenda.Operacao remarcar(String conta, long agendamentoId, NovoHorario novo, boolean jaFeitoNoSistema, String usuario);

    /** O horário foi ocupado direto no sistema da clínica: some da oferta do bot. */
    Agenda.Bloqueio ocupar(String conta, long profissionalId, LocalDateTime inicio, LocalDateTime fim, String usuario);

    Agenda.Bloqueio bloquear(String conta, Agenda.Bloqueio bloqueio, String usuario);

    boolean desbloquear(String conta, long bloqueioId);

    List<Agenda.Bloqueio> bloqueios(String conta, LocalDate de, LocalDate ate);

    Map<Agenda.Cadastro, List<Agenda.Item>> cadastros(String conta);

    Agenda.Item salvarItem(String conta, Agenda.Cadastro cadastro, Agenda.Item item);

    Agenda.Exclusao excluirItem(String conta, Agenda.Cadastro cadastro, long id);

    List<Agenda.Grade> grades(String conta);

    Agenda.Grade salvarGrade(String conta, Agenda.Grade grade);

    boolean removerGrade(String conta, long gradeId);

    List<Agenda.Paciente> buscarPacientes(String conta, String termo);

    HistoricoPaciente historicoPaciente(String conta, String cpf, LocalDate nascimento);

    List<Agenda.Pendencia> pendencias(String conta, Agenda.SituacaoPendencia situacao);

    boolean concluirPendencia(String conta, long pendenciaId, String codigoExterno, String observacao, String usuario);

    boolean reabrirPendencia(String conta, long pendenciaId, String usuario);

    Agenda.ResumoPendencias resumo(String conta);

    /** Consultas ativas do dia, para comparar com a agenda do sistema da clínica. */
    List<Agenda.Agendamento> conferencia(String conta, LocalDate dia);

    void conferir(String conta, long agendamentoId, Agenda.SituacaoConferencia situacao, String observacao, String usuario);
}
