package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;

/**
 * Tela /consultorio do médico. Todo método recebe o {@link Contexto} para validar que o atendimento pertence ao
 * profissional vinculado ao usuário. Falta de vínculo ou atendimento de outro profissional: o service lança
 * {@code Proibido}; entrada inválida: {@code IllegalArgumentException}; regra de negócio: {@code Operacao} com código.
 */
public interface ConsultorioUseCase {

    /** Quem está agindo: clínica, usuário (para achar o profissional) e nome gravado nos registros. */
    record Contexto(String conta, long usuarioId, String ator) {
    }

    record AnamneseInBound(
            String queixaPrincipal,
            String historiaDoencaAtual,
            String antecedentesPessoais,
            String antecedentesFamiliares,
            String medicamentosEmUso,
            String alergias,
            String habitos,
            String exameFisico,
            String avaliacao,
            String conduta
    ) {
    }

    record AlergiaInBound(String descricao) {
    }

    record EvolucaoInBound(String texto, BigDecimal pesoKg, BigDecimal alturaCm, LocalDate retornoPrevisto) {
    }

    record DiagnosticoInBound(String cid, String descricao, Atendimento.TipoDiagnostico tipo) {
    }

    record ReceitaInBound(Atendimento.TipoReceita tipo, String observacao, List<Atendimento.ReceitaItem> itens) {
    }

    /** desfecho nulo: ALTA. */
    record FinalizarInBound(
            Atendimento.MotivoAlta motivoAlta,
            Atendimento.Desfecho desfecho,
            String orientacao,
            LocalDate retornoPrevisto
    ) {
    }

    /** Receita pronta para imprimir: HTML já montado com clínica, paciente, itens, médico e conselho. */
    record Impressao(String html) {
    }

    /** Atendimentos do dia do profissional do usuário. */
    List<Atendimento.FilaItem> fila(Contexto ctx, LocalDate data);

    Atendimento.Operacao iniciarConsulta(Contexto ctx, long atendimentoId);

    /** Grava acesso_prontuario. */
    Atendimento.Prontuario prontuario(Contexto ctx, long atendimentoId, String ip);

    /** Cria ou atualiza o rascunho da anamnese. Operacao.id() é o id da anamnese. */
    Atendimento.Operacao salvarRascunhoAnamnese(Contexto ctx, long atendimentoId, AnamneseInBound anamnese);

    Atendimento.Operacao liberarAnamnese(Contexto ctx, long atendimentoId);

    Atendimento.Operacao inativarAnamnese(Contexto ctx, long anamneseId, String justificativa);

    Atendimento.Operacao criarEvolucao(Contexto ctx, long atendimentoId, EvolucaoInBound evolucao);

    Atendimento.Operacao editarEvolucao(Contexto ctx, long evolucaoId, EvolucaoInBound evolucao);

    Atendimento.Operacao liberarEvolucao(Contexto ctx, long evolucaoId);

    Atendimento.Operacao inativarEvolucao(Contexto ctx, long evolucaoId, String justificativa);

    Atendimento.Operacao incluirDiagnostico(Contexto ctx, long atendimentoId, DiagnosticoInBound diagnostico);

    Atendimento.Operacao inativarDiagnostico(Contexto ctx, long diagnosticoId, String justificativa);

    Atendimento.Operacao criarReceita(Contexto ctx, long atendimentoId, ReceitaInBound receita);

    Atendimento.Operacao editarReceita(Contexto ctx, long receitaId, ReceitaInBound receita);

    Atendimento.Operacao liberarReceita(Contexto ctx, long receitaId);

    Atendimento.Operacao cancelarReceita(Contexto ctx, long receitaId, String justificativa);

    /** Só receita liberada. Grava acesso_prontuario. */
    Impressao impressaoReceita(Contexto ctx, long receitaId, String ip);

    /** Folha da consulta finalizada para assinatura manual, só com registros liberados e ativos. Grava acesso_prontuario. */
    Impressao impressaoConsulta(Contexto ctx, long atendimentoId, String ip);

    Atendimento.Operacao finalizar(Contexto ctx, long atendimentoId, FinalizarInBound finalizacao);

    /** Libera de uma vez os rascunhos com conteúdo, descarta os vazios e finaliza. */
    Atendimento.Operacao finalizarEAssinar(Contexto ctx, long atendimentoId, FinalizarInBound finalizacao);

    Atendimento.Operacao incluirAlergia(Contexto ctx, long atendimentoId, AlergiaInBound alergia);

    Atendimento.Operacao inativarAlergia(Contexto ctx, long atendimentoId, long alergiaId, String justificativa);

    /** Atendimentos anteriores do paciente. Grava acesso_prontuario. */
    List<Atendimento.Prontuario> historicoDoPaciente(Contexto ctx, long pacienteId, String ip);

    /** Chama o próprio paciente que aguarda para o local informado (opcional, ex.: Consultório 3). */
    Atendimento.Operacao chamar(Contexto ctx, long atendimentoId, String local);

    /** Locais sugeridos para a chamada na clínica do usuário; vazia quando não configurada. */
    List<String> locaisDeChamada(Contexto ctx);

    /** Cadastros do CPF, cada um dizendo se o médico precisa informar motivo para abrir o prontuário. */
    List<Atendimento.PacienteEncontrado> pacientesPorCpf(Contexto ctx, String cpf);

    /**
     * Prontuário de todos os médicos da clínica (só registros liberados) e a linha do tempo completa. Paciente fora da
     * fila do médico no dia exige motivo. Grava acesso_prontuario com o motivo.
     */
    Atendimento.ProntuarioPaciente prontuarioDoPaciente(Contexto ctx, long pacienteId, String motivo, String ip);

    List<Atendimento.MedicamentoFavorito> favoritos(Contexto ctx);

    /** Mesmo medicamento, apresentação e posologia atualiza o existente. Operacao.id() é o favorito. */
    Atendimento.Operacao salvarFavorito(Contexto ctx, Atendimento.MedicamentoFavorito favorito);

    boolean excluirFavorito(Contexto ctx, long favoritoId);

    List<Atendimento.ModeloNota> modelos(Contexto ctx);

    /** id nulo cria. Atalho: 2 a 30 letras minúsculas, números, hífen ou sublinhado. */
    Atendimento.Operacao salvarModelo(Contexto ctx, Atendimento.ModeloNota modelo);

    boolean excluirModelo(Contexto ctx, long modeloId);
}
