package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
            String paciente
    ) {
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
