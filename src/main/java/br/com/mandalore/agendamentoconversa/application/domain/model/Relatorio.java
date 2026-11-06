package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.LocalDate;
import java.util.Map;

public final class Relatorio {

    private Relatorio() {
    }

    public enum Tipo {
        CURADORIA, GESTOR, CONVERSAO, AGENDA
    }

    /**
     * Desfecho das conversas com mensagem no período, contadas pelo número da conversa no canal.
     * {@code convertidas}: o bot marcou consulta ou exame. {@code atendente}: passou para atendente sem marcar.
     * O que a equipe marca depois do transbordo não está na trilha.
     */
    public record Conversao(long conversas, long convertidas, long atendente) {

        public static final Conversao VAZIA = new Conversao(0, 0, 0);

        public long semDesfecho() {
            return Math.max(0, conversas - convertidas - atendente);
        }
    }

    /**
     * Números da trilha num período, sem fala nem dado de paciente.
     * {@code escritas}: consulta, exame, confirmar, cancelar, remarcar. {@code transbordos}: nome do {@link Setor}.
     */
    public record Numeros(
            long mensagens,
            long conversas,
            long naoEntendidas,
            long baixaConfianca,
            long pendentes,
            long ensinadas,
            long faqRespondidas,
            Map<String, Long> faqSemResposta,
            Map<String, Long> escritas,
            Map<String, Long> transbordos,
            long orcamentos,
            Map<String, Long> falhas,
            Conversao conversao
    ) {

        public Numeros {
            faqSemResposta = Map.copyOf(faqSemResposta);
            escritas = Map.copyOf(escritas);
            transbordos = Map.copyOf(transbordos);
            falhas = Map.copyOf(falhas);
            conversao = conversao == null ? Conversao.VAZIA : conversao;
        }

        public long escritas(String tipo) {
            return escritas.getOrDefault(tipo, 0L);
        }

        public long totalTransbordos() {
            return transbordos.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** Números de {@code de} a {@code ate}, os dois dias inclusive, no fuso de São Paulo. */
    public record Periodo(LocalDate de, LocalDate ate, Numeros numeros) {
    }
}
