package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Fluxo clínico de uma consulta: chegada na recepção, consulta no consultório e alta. Espelha o
 * {@code ATENDIMENTO_PACIENTE} do Tasy no schema {@code atendimento}. Registros clínicos (anamnese, evolução,
 * receita) ficam em rascunho até a liberação; depois só se corrigem por inativação com justificativa.
 */
public final class Atendimento {

    private Atendimento() {
    }

    public enum Status {
        AGUARDANDO, EM_CONSULTA, FINALIZADO, CANCELADO;

        /** Estados finais: nada mais muda no atendimento. */
        public boolean encerrado() {
            return this == FINALIZADO || this == CANCELADO;
        }
    }

    public enum MotivoAlta {
        ALTA_MEDICA, ENCAMINHADO, DESISTENCIA, OUTRO
    }

    public enum Desfecho {
        ALTA, RETORNO, ENCAMINHAMENTO
    }

    public enum TipoDiagnostico {
        PRINCIPAL, SECUNDARIO
    }

    public enum TipoReceita {
        COMUM, ESPECIAL
    }

    /** Linha de {@code atendimento.atendimento_paciente}. */
    public record Ficha(
            long nrAtendimento,
            long agendamentoId,
            long pacienteId,
            String paciente,
            long profissionalId,
            String profissional,
            long especialidadeId,
            String especialidade,
            Long convenioId,
            Status status,
            LocalDateTime dtEntrada,
            LocalDateTime dtAtendMedico,
            LocalDateTime dtFimConsulta,
            LocalDateTime dtAlta,
            MotivoAlta motivoAlta,
            String usuarioEntrada,
            String usuarioAtendimento,
            String usuarioAlta,
            LocalDateTime dtCancelamento,
            String usuarioCancelamento,
            String motivoCancelamento
    ) {
    }

    /**
     * Linha da fila da recepção. Sem conteúdo clínico (LGPD). atendimentoId e chegadaEm só existem depois da
     * chegada; esperaMin é o tempo desde a chegada enquanto o paciente aguarda.
     */
    public record FilaItem(
            long agendamentoId,
            Long atendimentoId,
            LocalDateTime horario,
            String paciente,
            String profissional,
            String especialidade,
            String status,
            LocalDateTime chegadaEm,
            Long esperaMin
    ) {
    }

    /** Campos da anamnese por seção ({@code atendimento.anamnese_paciente}). Liberada quando dtLiberacao existe. */
    public record Anamnese(
            Long id,
            long nrAtendimento,
            long pacienteId,
            long profissionalId,
            String queixaPrincipal,
            String historiaDoencaAtual,
            String antecedentesPessoais,
            String antecedentesFamiliares,
            String medicamentosEmUso,
            String alergias,
            String habitos,
            String exameFisico,
            LocalDateTime dtRegistro,
            LocalDateTime dtLiberacao,
            boolean ativa,
            LocalDateTime dtInativacao,
            String usuarioInativacao,
            String justificativa,
            String usuario
    ) {

        public boolean liberada() {
            return dtLiberacao != null;
        }
    }

    /** {@code atendimento.evolucao_paciente}. */
    public record Evolucao(
            Long id,
            long nrAtendimento,
            long pacienteId,
            long profissionalId,
            String texto,
            BigDecimal pesoKg,
            BigDecimal alturaCm,
            LocalDate retornoPrevisto,
            LocalDateTime dtEvolucao,
            LocalDateTime dtLiberacao,
            boolean ativa,
            LocalDateTime dtInativacao,
            String usuarioInativacao,
            String justificativa,
            String usuario
    ) {

        public boolean liberada() {
            return dtLiberacao != null;
        }
    }

    /** {@code atendimento.diagnostico_doenca}; cid no formato A00 ou A00.0. */
    public record Diagnostico(
            Long id,
            long nrAtendimento,
            long pacienteId,
            long profissionalId,
            String cid,
            String descricao,
            TipoDiagnostico tipo,
            LocalDateTime dtDiagnostico,
            boolean ativo,
            LocalDateTime dtInativacao,
            String usuarioInativacao,
            String justificativa,
            String usuario
    ) {
    }

    /** {@code atendimento.med_receita} com os itens em ordem. */
    public record Receita(
            Long id,
            long nrAtendimento,
            long pacienteId,
            long profissionalId,
            TipoReceita tipo,
            String observacao,
            LocalDateTime dtReceita,
            LocalDateTime dtLiberacao,
            LocalDate dtValidade,
            boolean ativa,
            LocalDateTime dtInativacao,
            String usuarioInativacao,
            String justificativa,
            String usuario,
            List<ReceitaItem> itens
    ) {

        public Receita {
            itens = itens == null ? List.of() : List.copyOf(itens);
        }

        public boolean liberada() {
            return dtLiberacao != null;
        }
    }

    /** {@code atendimento.med_receita_item}; ordem começa em 1. */
    public record ReceitaItem(
            int ordem,
            String medicamento,
            String apresentacao,
            String posologia,
            String via,
            String quantidade,
            boolean usoContinuo,
            String observacao
    ) {
    }

    /** {@code atendimento.atendimento_alta}. */
    public record Alta(
            long nrAtendimento,
            Desfecho desfecho,
            String orientacao,
            LocalDate retornoPrevisto,
            LocalDateTime dtLiberacao,
            String usuario
    ) {
    }

    /** Histórico do atendimento. Nunca guarda conteúdo clínico, só tipo, usuário e um detalhe curto. */
    public record Evento(String tipo, String usuario, String detalhe, Instant em) {
    }

    /** Visão completa do atendimento para o médico. */
    public record Prontuario(
            Ficha atendimento,
            Anamnese anamnese,
            List<Evolucao> evolucoes,
            List<Diagnostico> diagnosticos,
            List<Receita> receitas,
            Alta alta
    ) {

        public Prontuario {
            evolucoes = evolucoes == null ? List.of() : List.copyOf(evolucoes);
            diagnosticos = diagnosticos == null ? List.of() : List.copyOf(diagnosticos);
            receitas = receitas == null ? List.of() : List.copyOf(receitas);
        }
    }

    /** Resultado de uma transição ou gravação: código de negócio e o id criado ou afetado, quando houver. */
    public record Operacao(CodigoNegocio codigo, Long id) {

        public static Operacao sucesso(Long id) {
            return new Operacao(CodigoNegocio.SUCESSO, id);
        }

        public static Operacao falha(CodigoNegocio codigo) {
            return new Operacao(codigo, null);
        }
    }
}
