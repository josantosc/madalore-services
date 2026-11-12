package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;

/**
 * Agenda do Mandalore no PostgreSQL. Todo método recebe a conta do Chatwoot (a clínica) e só enxerga as linhas dela.
 * As operações de escrita gravam o agendamento, o histórico e as pendências na mesma transação.
 */
public interface AgendaMandalorePort {

    List<String> contas();

    List<Agenda.Item> itens(String conta, Agenda.Cadastro cadastro);

    Agenda.Item salvarItem(String conta, Agenda.Cadastro cadastro, Agenda.Item item);

    /**
     * Exclui o cadastro com os horários de atendimento e bloqueios dele. Se já houver consulta com ele, só desativa o
     * cadastro e os horários.
     */
    Agenda.Exclusao excluirItem(String conta, Agenda.Cadastro cadastro, long id);

    /** Usuário e conselho de cada profissional que tem algum dado preenchido. */
    Map<Long, Agenda.Credencial> credenciais(String conta);

    /** Grava usuário e conselho. Recusa usuário já vinculado a outro profissional. */
    void salvarCredencial(String conta, long profissionalId, Agenda.Credencial credencial);

    /** Usuários ativos com acesso à clínica. */
    List<Agenda.UsuarioVinculavel> usuariosDaConta(String conta);

    /** Especialidades vinculadas, por profissional. */
    Map<Long, List<Long>> especialidadesDosProfissionais(String conta);

    /** Troca todas as especialidades vinculadas ao profissional pelas informadas. */
    void vincularEspecialidades(String conta, long profissionalId, List<Long> especialidades);

    List<Agenda.Grade> grades(String conta);

    Optional<Agenda.Grade> grade(String conta, long id);

    Agenda.Grade salvarGrade(String conta, Agenda.Grade grade);

    boolean removerGrade(String conta, long id);

    List<Agenda.Bloqueio> bloqueios(String conta, LocalDateTime de, LocalDateTime ate);

    Agenda.Bloqueio bloquear(String conta, Agenda.Bloqueio bloqueio);

    boolean desbloquear(String conta, long id);

    Optional<Agenda.Paciente> paciente(String conta, String cpf, LocalDate nascimento);

    List<Agenda.Paciente> buscarPacientes(String conta, String termo);

    /** Agendamentos com início no intervalo. ativos: só marcados e confirmados. */
    List<Agenda.Agendamento> agendamentos(String conta, LocalDateTime de, LocalDateTime ate, boolean ativos);

    List<Agenda.Agendamento> agendamentosDoPaciente(String conta, long pacienteId);

    Optional<Agenda.Agendamento> agendamento(String conta, long id);

    List<Agenda.Evento> historico(String conta, long agendamentoId);

    Agenda.Operacao marcar(String conta, Agenda.Marcacao marcacao);

    Agenda.Operacao confirmar(String conta, long agendamentoId, Agenda.Alteracao alteracao);

    Agenda.Operacao cancelar(String conta, long agendamentoId, Agenda.Alteracao alteracao);

    Agenda.Operacao remarcar(String conta, long agendamentoId, Agenda.Marcacao nova, Agenda.Alteracao alteracao);

    /** situacao nula: abertas e as resolvidas nos últimos 7 dias. */
    List<Agenda.Pendencia> pendencias(String conta, Agenda.SituacaoPendencia situacao);

    Optional<Agenda.Pendencia> pendencia(String conta, long id);

    boolean concluirPendencia(String conta, long id, String usuario, String codigoExterno, String observacao);

    boolean reabrirPendencia(String conta, long id, String usuario);

    long pendenciasAbertasDaConversa(String conta, String conversaId);

    /** conta nula: todas as clínicas da instalação. */
    Agenda.ResumoPendencias resumo(String conta, LocalDateTime agora);

    void conferir(String conta, long agendamentoId, Agenda.SituacaoConferencia situacao, String observacao, String usuario);
}
