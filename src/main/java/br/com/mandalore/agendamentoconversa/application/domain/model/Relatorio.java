package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.util.Map;

public final class Relatorio {

    private Relatorio() {
    }

    public enum Tipo {
        CURADORIA, GESTOR
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
            Map<String, Long> falhas
    ) {

        public Numeros {
            faqSemResposta = Map.copyOf(faqSemResposta);
            escritas = Map.copyOf(escritas);
            transbordos = Map.copyOf(transbordos);
            falhas = Map.copyOf(falhas);
        }

        public long escritas(String tipo) {
            return escritas.getOrDefault(tipo, 0L);
        }

        public long totalTransbordos() {
            return transbordos.values().stream().mapToLong(Long::longValue).sum();
        }
    }
}
