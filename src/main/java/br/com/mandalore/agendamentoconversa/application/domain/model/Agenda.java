package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Agenda do Mandalore para clínicas sem integração. Enquanto não houver integração, a agenda oficial continua no
 * sistema da clínica: cada alteração feita aqui vira uma {@link Pendencia} para a recepção lançar lá.
 */
public final class Agenda {

    private Agenda() {
    }

    public enum Cadastro {
        PROFISSIONAL("profissional"), ESPECIALIDADE("especialidade"), CONVENIO("convênio");

        private final String titulo;

        Cadastro(String titulo) {
            this.titulo = titulo;
        }

        public String titulo() {
            return titulo;
        }
    }

    /** Resultado de excluir um cadastro: quem já foi usado em consulta só é desativado, para manter o histórico. */
    public enum Exclusao {
        EXCLUIDO, DESATIVADO, NAO_ENCONTRADO
    }

    public enum Status {
        MARCADO, CONFIRMADO, CANCELADO, REMARCADO;

        public boolean ativo() {
            return this == MARCADO || this == CONFIRMADO;
        }
    }

    public enum Origem {
        BOT, RECEPCAO
    }

    public enum TipoPendencia {
        LANCAR("Lançar no sistema da clínica"),
        CANCELAR("Cancelar no sistema da clínica"),
        REMARCAR("Remarcar no sistema da clínica"),
        CONFIRMAR("Marcar como confirmado no sistema da clínica"),
        CONFERIR("Conferir divergência com o sistema da clínica");

        private final String titulo;

        TipoPendencia(String titulo) {
            this.titulo = titulo;
        }

        public String titulo() {
            return titulo;
        }
    }

    public enum SituacaoPendencia {
        ABERTA, CONCLUIDA, DESCARTADA
    }

    public enum SituacaoConferencia {
        CONFERIDO, DIVERGENTE
    }

    public record Item(Long id, String nome, boolean ativo) {
    }

    /**
     * Horário de atendimento: semanal (diaSemana na ISO, 1 = segunda, com vigência opcional) ou só numa data.
     * Preenche diaSemana ou data, nunca os dois.
     */
    public record Grade(
            Long id,
            long profissionalId,
            long especialidadeId,
            Integer diaSemana,
            LocalDate data,
            LocalDate vigenciaInicio,
            LocalDate vigenciaFim,
            LocalTime inicio,
            LocalTime fim,
            int duracaoMin,
            boolean ofereceBot,
            String local,
            boolean ativo
    ) {

        public boolean valeEm(LocalDate dia) {
            if (data != null) {
                return data.equals(dia);
            }
            return diaSemana != null && diaSemana == dia.getDayOfWeek().getValue()
                    && (vigenciaInicio == null || !dia.isBefore(vigenciaInicio))
                    && (vigenciaFim == null || !dia.isAfter(vigenciaFim));
        }
    }

    /** Sem profissional, vale para a clínica inteira. */
    public record Bloqueio(Long id, Long profissionalId, LocalDateTime inicio, LocalDateTime fim, String motivo, String criadoPor) {

        public boolean atinge(long profissional, LocalDateTime de, LocalDateTime ate) {
            return (profissionalId == null || profissionalId == profissional) && inicio.isBefore(ate) && de.isBefore(fim);
        }
    }

    public record Paciente(Long id, String cpf, LocalDate nascimento, String nome, String telefone) {
    }

    public record Agendamento(
            long id,
            long profissionalId,
            String profissional,
            long especialidadeId,
            String especialidade,
            Long convenioId,
            String convenio,
            Paciente paciente,
            LocalDateTime inicio,
            LocalDateTime fim,
            String local,
            Status status,
            Origem origem,
            String codigoExterno,
            String conversaId,
            Long remarcadoDe,
            List<TipoPendencia> pendenciasAbertas,
            SituacaoConferencia conferencia
    ) {

        public Agendamento {
            pendenciasAbertas = pendenciasAbertas == null ? List.of() : List.copyOf(pendenciasAbertas);
        }
    }

    /** Horário gerado pela grade. */
    public record Horario(
            LocalDateTime inicio,
            LocalDateTime fim,
            long gradeId,
            long profissionalId,
            long especialidadeId,
            String local,
            boolean ofereceBot
    ) {
    }

    /** codigoExterno preenchido: a recepção já lançou no sistema da clínica e não precisa de pendência. */
    public record Marcacao(
            String chave,
            long profissionalId,
            long especialidadeId,
            Long convenioId,
            LocalDateTime inicio,
            LocalDateTime fim,
            String local,
            Paciente paciente,
            Origem origem,
            String usuario,
            String conversaId,
            String codigoExterno
    ) {
    }

    /** jaFeitoNoSistema: a recepção já fez a mesma alteração no sistema da clínica. */
    public record Alteracao(String chave, Long pacienteId, Origem origem, String usuario, boolean jaFeitoNoSistema) {
    }

    public record Operacao(CodigoNegocio codigo, Long agendamentoId, List<Pendencia> pendencias) {

        public Operacao {
            pendencias = pendencias == null ? List.of() : List.copyOf(pendencias);
        }

        public static Operacao falha(CodigoNegocio codigo) {
            return new Operacao(codigo, null, List.of());
        }
    }

    public record Pendencia(
            long id,
            long agendamentoId,
            TipoPendencia tipo,
            SituacaoPendencia situacao,
            String resumo,
            LocalDateTime prazo,
            Instant criadaEm,
            Instant concluidaEm,
            String concluidaPor,
            String codigoExterno,
            String observacao,
            String conversaId,
            LocalDateTime inicio,
            String paciente
    ) {

        public boolean atrasada(LocalDateTime agora) {
            return situacao == SituacaoPendencia.ABERTA && prazo.isBefore(agora);
        }
    }

    public record Evento(String tipo, String usuario, String detalhe, Instant em) {
    }

    public record ResumoPendencias(
            long abertas,
            long atrasadas,
            LocalDateTime prazoMaisAntigo,
            long divergencias,
            long amanhaSemConferencia
    ) {
    }

    public enum SituacaoHorario {
        LIVRE, OCUPADO, BLOQUEADO
    }

    /** Linha da visão do dia na tela da recepção. */
    public record LinhaDia(
            LocalDateTime inicio,
            LocalDateTime fim,
            long profissionalId,
            String profissional,
            Long especialidadeId,
            String especialidade,
            String local,
            Long gradeId,
            boolean ofereceBot,
            SituacaoHorario situacao,
            Agendamento agendamento,
            Bloqueio bloqueio
    ) {
    }

    public record ResumoDia(LocalDate dia, long livres, long livresBot, long ocupados) {
    }
}
