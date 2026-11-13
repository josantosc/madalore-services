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
     * chegada; esperaMin é o tempo desde a chegada enquanto o paciente aguarda; chamadoEm e localChamada, a última
     * chamada.
     */
    public record FilaItem(
            long agendamentoId,
            Long atendimentoId,
            LocalDateTime horario,
            String paciente,
            String cpf,
            String profissional,
            String especialidade,
            String status,
            LocalDateTime chegadaEm,
            Long esperaMin,
            LocalDateTime chamadoEm,
            String localChamada
    ) {
    }

    /** Chamada no painel da sala de espera. paciente sai abreviado do service (Maria S. O.), nunca o nome completo. */
    public record Chamada(String paciente, String profissional, String local, LocalDateTime chamadoEm) {
    }

    /** Paciente que chegou sem agendamento. Paciente novo é cadastrado pelo CPF e nascimento. */
    public record Encaixe(
            long profissionalId,
            long especialidadeId,
            Long convenioId,
            String cpf,
            LocalDate nascimento,
            String nome,
            String telefone
    ) {
    }

    /** Item de cadastro para os seletores da recepção. */
    public record Opcao(long id, String nome) {
    }

    /** Profissional ativo com as especialidades que atende. */
    public record ProfissionalEncaixe(long id, String nome, List<Opcao> especialidades) {

        public ProfissionalEncaixe {
            especialidades = especialidades == null ? List.of() : List.copyOf(especialidades);
        }
    }

    public record CadastrosEncaixe(List<ProfissionalEncaixe> profissionais, List<Opcao> convenios) {

        public CadastrosEncaixe {
            profissionais = profissionais == null ? List.of() : List.copyOf(profissionais);
            convenios = convenios == null ? List.of() : List.copyOf(convenios);
        }
    }

    /** Paciente achado na busca da recepção. */
    public record PacienteResumo(long id, String nome, String cpf, LocalDate nascimento, String telefone) {
    }

    /** {@code atendimento.medicamento_favorito}: item de receita salvo pelo profissional. */
    public record MedicamentoFavorito(
            Long id,
            String medicamento,
            String apresentacao,
            String posologia,
            String via,
            String quantidade,
            boolean usoContinuo,
            String observacao
    ) {
    }

    /** {@code atendimento.medicamento_rotina}: sugestão de item de receita para todos os médicos da clínica. */
    public record MedicamentoRotina(
            Long id,
            String medicamento,
            String apresentacao,
            String posologia,
            String via,
            boolean usoContinuo
    ) {
    }

    /** {@code atendimento.modelo_nota}: texto do profissional expandido por {@code .atalho}. */
    public record ModeloNota(Long id, String atalho, String titulo, String texto) {
    }

    /**
     * Nota SOAP da consulta ({@code atendimento.anamnese_paciente}): subjetivo (queixa a hábitos), objetivo (exame
     * físico), avaliação e conduta. Liberada quando dtLiberacao existe. alergias é legado: a alergia vigente fica em
     * {@link Alergia}.
     */
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
            String avaliacao,
            String conduta,
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

    /** Evento da agenda ou do atendimento na linha do tempo do paciente. Detalhe curto, sem conteúdo clínico. */
    public record EventoPaciente(Instant em, String tipo, String usuario, String detalhe) {
    }

    /** Um agendamento do paciente, com o atendimento (quando houve chegada) e os eventos dos dois em ordem. */
    public record VisitaPaciente(
            long agendamentoId,
            LocalDateTime inicio,
            String profissional,
            String especialidade,
            String convenio,
            String statusAgendamento,
            String origem,
            Long nrAtendimento,
            String statusAtendimento,
            String localChamada,
            List<EventoPaciente> eventos
    ) {
        public VisitaPaciente {
            eventos = eventos == null ? List.of() : List.copyOf(eventos);
        }
    }

    /** O que a recepção vê do paciente: cadastro e linha do tempo administrativa. */
    public record FichaPaciente(PacienteResumo paciente, List<VisitaPaciente> visitas) {
        public FichaPaciente {
            visitas = visitas == null ? List.of() : List.copyOf(visitas);
        }
    }

    /** Prontuário de todos os médicos da clínica, só com registros liberados, mais a linha do tempo completa. */
    public record ProntuarioPaciente(PacienteResumo paciente, List<Alergia> alergias, List<Prontuario> atendimentos,
                                     List<VisitaPaciente> visitas) {
        public ProntuarioPaciente {
            alergias = alergias == null ? List.of() : List.copyOf(alergias);
            atendimentos = atendimentos == null ? List.of() : List.copyOf(atendimentos);
            visitas = visitas == null ? List.of() : List.copyOf(visitas);
        }
    }

    /** Cadastro achado pelo CPF. exigeMotivo: o paciente não está na fila do médico no dia. */
    public record PacienteEncontrado(PacienteResumo paciente, boolean exigeMotivo) {
    }

    /** {@code atendimento.alergia_paciente}: vale para todas as consultas do paciente enquanto ativa. */
    public record Alergia(Long id, long pacienteId, String descricao, LocalDateTime dtRegistro, String usuario) {
    }

    /**
     * Faixa fixa do prontuário. alergiasAnteriores é o texto de alergias da última anamnese liberada, de antes do
     * cadastro de alergias do paciente; vazio quando não houver.
     */
    public record CabecalhoPaciente(
            long pacienteId,
            String nome,
            LocalDate nascimento,
            String convenio,
            List<Alergia> alergias,
            String alergiasAnteriores
    ) {

        public CabecalhoPaciente {
            alergias = alergias == null ? List.of() : List.copyOf(alergias);
        }
    }

    /** Visão completa do atendimento para o médico. paciente vem nulo no histórico. */
    public record Prontuario(
            Ficha atendimento,
            Anamnese anamnese,
            List<Evolucao> evolucoes,
            List<Diagnostico> diagnosticos,
            List<Receita> receitas,
            Alta alta,
            CabecalhoPaciente paciente
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
