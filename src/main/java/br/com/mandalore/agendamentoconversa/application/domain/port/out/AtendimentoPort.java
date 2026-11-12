package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;

/**
 * Persistência do fluxo clínico (schema {@code atendimento}). Todo método recebe a conta e só enxerga linhas dela.
 * Transições e gravações são transacionais, travam a linha e devolvem {@link Atendimento.Operacao}; o service valida
 * posse e entrada, o adapter reforça estado e conta na própria consulta.
 */
public interface AtendimentoPort {

    /** Profissional da agenda vinculado ao usuário, com conselho. */
    record ProfissionalClinico(long id, String nome, String conselhoNumero, String conselhoUf) {
    }

    /** Dados da clínica e do paciente para a impressão da receita. */
    record CabecalhoReceita(String clinica, String paciente, String cpf) {
    }

    /** Contagens para finalizar: registros liberados ativos e rascunhos abertos (anamnese, evolução e receita). */
    record ContagemClinica(int anamneseEvolucaoLiberadas, int rascunhos) {
    }

    // ---- recepção ----

    /** Agendamentos do dia (todos os profissionais) com chegada e espera. */
    List<Atendimento.FilaItem> fila(String conta, LocalDate data);

    /** Agendamentos do dia de um profissional que já chegaram (AGUARDANDO e adiante). */
    List<Atendimento.FilaItem> filaDoProfissional(String conta, long profissionalId, LocalDate data);

    /** Transação da seção 9.1. Operacao.id() é o nrAtendimento; idempotente. */
    Atendimento.Operacao registrarChegada(String conta, long agendamentoId, String ator);

    /** Seção 9.5. Volta o agendamento a CONFIRMADO ou MARCADO. */
    Atendimento.Operacao desfazerChegada(String conta, long nrAtendimento, String motivo, String ator);

    List<Atendimento.Evento> eventos(String conta, long nrAtendimento);

    // ---- consultório: leitura ----

    Optional<ProfissionalClinico> profissionalDoUsuario(String conta, long usuarioId);

    Optional<Atendimento.Ficha> atendimento(String conta, long nrAtendimento);

    Optional<Atendimento.Prontuario> prontuario(String conta, long nrAtendimento);

    /** Atendimentos do paciente, do mais recente ao mais antigo. */
    List<Atendimento.Prontuario> historicoDoPaciente(String conta, long pacienteId);

    Optional<Atendimento.Anamnese> anamnese(String conta, long anamneseId);

    Optional<Atendimento.Evolucao> evolucao(String conta, long evolucaoId);

    Optional<Atendimento.Diagnostico> diagnostico(String conta, long diagnosticoId);

    Optional<Atendimento.Receita> receita(String conta, long receitaId);

    Optional<CabecalhoReceita> cabecalhoReceita(String conta, long receitaId);

    ContagemClinica contagemParaFinalizar(String conta, long nrAtendimento);

    /** PEP_ACESSO. nrAtendimento pode ser nulo (histórico do paciente). */
    void registrarAcesso(String conta, Long nrAtendimento, long pacienteId, String acao, String usuario, String ip);

    // ---- consultório: transições ----

    /** Seção 9.2. Violação de uk_atendimento_profissional_em_consulta vira PROFISSIONAL_EM_CONSULTA. */
    Atendimento.Operacao iniciarConsulta(String conta, long nrAtendimento, long profissionalId, String ator);

    /** Cria ou atualiza a anamnese ativa em rascunho. Operacao.id() é o id da anamnese. */
    Atendimento.Operacao salvarRascunhoAnamnese(String conta, long nrAtendimento, long profissionalId,
                                                Atendimento.Anamnese anamnese, String ator);

    /** Libera a anamnese ativa em rascunho do atendimento. */
    Atendimento.Operacao liberarAnamnese(String conta, long nrAtendimento, long profissionalId, String ator);

    Atendimento.Operacao inativarAnamnese(String conta, long anamneseId, long profissionalId, String justificativa,
                                          String ator);

    Atendimento.Operacao criarEvolucao(String conta, long nrAtendimento, long profissionalId,
                                       Atendimento.Evolucao evolucao, String ator);

    /** Só rascunho; senão REGISTRO_JA_LIBERADO. */
    Atendimento.Operacao editarEvolucao(String conta, long evolucaoId, long profissionalId,
                                        Atendimento.Evolucao evolucao, String ator);

    Atendimento.Operacao liberarEvolucao(String conta, long evolucaoId, long profissionalId, String ator);

    Atendimento.Operacao inativarEvolucao(String conta, long evolucaoId, long profissionalId, String justificativa,
                                          String ator);

    /** UK de principal ativo e de CID repetido ativo viram códigos de negócio. */
    Atendimento.Operacao incluirDiagnostico(String conta, long nrAtendimento, long profissionalId,
                                            Atendimento.Diagnostico diagnostico, String ator);

    Atendimento.Operacao inativarDiagnostico(String conta, long diagnosticoId, long profissionalId,
                                             String justificativa, String ator);

    /** Grava a receita e os itens em rascunho. */
    Atendimento.Operacao criarReceita(String conta, long nrAtendimento, long profissionalId,
                                      Atendimento.Receita receita, String ator);

    /** Só rascunho: troca observação e reinsere os itens. */
    Atendimento.Operacao editarReceita(String conta, long receitaId, long profissionalId,
                                       Atendimento.Receita receita, String ator);

    /** Libera com a validade calculada pelo service (hoje + validade-dias). */
    Atendimento.Operacao liberarReceita(String conta, long receitaId, long profissionalId, LocalDate validade,
                                        String ator);

    Atendimento.Operacao cancelarReceita(String conta, long receitaId, long profissionalId, String justificativa,
                                         String ator);

    /** Seção 9.4: grava a alta, finaliza o atendimento e põe o agendamento em EXECUTADO. */
    Atendimento.Operacao finalizar(String conta, long nrAtendimento, long profissionalId,
                                   Atendimento.MotivoAlta motivoAlta, Atendimento.Alta alta, String ator);
}
