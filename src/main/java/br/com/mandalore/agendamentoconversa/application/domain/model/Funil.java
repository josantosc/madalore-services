package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Funil de orçamento: cada orçamento vira uma oportunidade até o paciente agendar ou desistir. */
public final class Funil {

    public static final String ETIQUETA_ORCAMENTO = "orcamento";

    private Funil() {
    }

    public enum Etapa {
        NOVO, ORCAMENTO_ENVIADO, EM_NEGOCIACAO, AGENDADO, PERDIDO;

        public boolean aberta() {
            return this != AGENDADO && this != PERDIDO;
        }
    }

    public enum Origem {
        ORCAMENTO_FOTO, ORCAMENTO_TEXTO, ORCAMENTO_MANUAL
    }

    public enum MotivoPerda {
        PRECO, CONVENIO_NAO_ATENDIDO, FEZ_EM_OUTRO_LUGAR, SEM_HORARIO, DESISTIU, SEM_RESPOSTA
    }

    /**
     * Sem dado de saúde: nem nome de exame, nem texto da conversa, nem CPF. O detalhe fica no Chatwoot.
     * {@code valor} e {@code qtdItens} nulos no orçamento manual. {@code paciente}: nome do contato no Chatwoot;
     * {@code responsavel}: atendente atribuído à conversa ou, sem atribuição, o último que respondeu.
     * {@code toque}: contato da cadência que vence em {@code proximaAcao} (1 em diante; 0 enquanto Novo).
     * {@code encerraEm}: cadência cumprida sem resposta; nesse dia fecha como perdida.
     */
    public record Oportunidade(
            long id,
            String conta,
            String conversa,
            Origem origem,
            Etapa etapa,
            MotivoPerda motivoPerda,
            BigDecimal valor,
            Integer qtdItens,
            String responsavel,
            LocalDate proximaAcao,
            Instant ultimaRespostaEm,
            Instant abertaEm,
            Instant fechadaEm,
            String paciente,
            int toque,
            LocalDate encerraEm
    ) {
    }

    /** Regras gravadas pela clínica na tela, com quem alterou por último. */
    public record RegrasSalvas(Regras regras, String atualizadaPor, Instant atualizadaEm) {
    }

    /** Só contagens, sem paciente: vai para o resumo diário. */
    public record ResumoRetornos(long doDia, Map<String, Long> atrasadosPorResponsavel) {

        public ResumoRetornos {
            atrasadosPorResponsavel = atrasadosPorResponsavel == null ? Map.of() : Map.copyOf(atrasadosPorResponsavel);
        }

        public long atrasados() {
            return atrasadosPorResponsavel.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * Cadência de retorno de uma clínica. {@code cadencia}: dias úteis até cada toque, contados do toque anterior
     * (o primeiro, do envio do orçamento ou da última mensagem do paciente). {@code diasEncerramento}: dias úteis
     * depois do último toque sem resposta para fechar como perdida. Dias úteis pulam domingo, os feriados nacionais,
     * os {@code feriados} locais da clínica e o sábado, a não ser com {@code sabadoUtil}.
     */
    public record Regras(List<Integer> cadencia, int diasEncerramento, boolean sabadoUtil, Set<LocalDate> feriados) {

        public static final int MAX_TOQUES = 10;
        public static final int MAX_DIAS = 60;
        public static final int MAX_FERIADOS = 100;

        public Regras {
            if (cadencia == null || cadencia.isEmpty() || cadencia.size() > MAX_TOQUES) {
                throw new IllegalArgumentException("Cadastre de 1 a " + MAX_TOQUES + " contatos");
            }
            if (cadencia.stream().anyMatch(dias -> dias == null || dias < 1 || dias > MAX_DIAS)) {
                throw new IllegalArgumentException("Cada contato precisa de 1 a " + MAX_DIAS + " dias úteis");
            }
            if (diasEncerramento < 1 || diasEncerramento > MAX_DIAS) {
                throw new IllegalArgumentException("O prazo para encerrar precisa de 1 a " + MAX_DIAS + " dias úteis");
            }
            if (feriados != null && feriados.size() > MAX_FERIADOS) {
                throw new IllegalArgumentException("Cadastre no máximo " + MAX_FERIADOS + " feriados locais");
            }
            cadencia = List.copyOf(cadencia);
            feriados = feriados == null ? Set.of() : Set.copyOf(feriados);
        }

        public int toques() {
            return cadencia.size();
        }

        /** Dias úteis do toque anterior até o toque {@code toque} (1 em diante). */
        public int diasAte(int toque) {
            return cadencia.get(Math.min(Math.max(toque, 1), toques()) - 1);
        }

        public boolean diaUtil(LocalDate dia) {
            return dia.getDayOfWeek() != DayOfWeek.SUNDAY
                    && (sabadoUtil || dia.getDayOfWeek() != DayOfWeek.SATURDAY)
                    && !feriados.contains(dia) && !FeriadosNacionais.feriado(dia);
        }

        public LocalDate diasUteis(LocalDate de, int dias) {
            LocalDate data = de;
            int restantes = dias;
            while (restantes > 0) {
                data = data.plusDays(1);
                if (diaUtil(data)) {
                    restantes--;
                }
            }
            return data;
        }
    }

    /**
     * Evento do webhook do Chatwoot que mexe no funil. {@code etiquetas}: as que acabaram de ser adicionadas.
     * {@code paciente} e {@code atribuido}: nome do contato e do atendente atribuído, quando o webhook traz.
     * {@code CONVERSA}: conversa atualizada (etiquetas, atribuição).
     */
    public record Evento(Tipo tipo, String conta, String conversa, String atendente, List<String> etiquetas,
                         String paciente, String atribuido) {

        public enum Tipo {
            MENSAGEM_PACIENTE, MENSAGEM_ATENDENTE, CONVERSA
        }

        public Evento {
            etiquetas = etiquetas == null ? List.of() : List.copyOf(etiquetas);
        }

        public Evento(Tipo tipo, String conta, String conversa, String atendente, List<String> etiquetas) {
            this(tipo, conta, conversa, atendente, etiquetas, null, null);
        }
    }
}
