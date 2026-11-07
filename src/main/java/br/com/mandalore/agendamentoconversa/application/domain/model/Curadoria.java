package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class Curadoria {

    private Curadoria() {
    }

    public enum Acao {
        ENSINAR, FORA_ESCOPO, IGNORAR
    }

    /** Fala (mascarada) que caiu no fallback ou teve confiança baixa, agrupada pelo texto. */
    public record Frase(
            String texto,
            long vezes,
            String intent,
            Double confianca,
            boolean fallback,
            Instant ultima
    ) {
    }

    public record Dia(LocalDate dia, long mensagens, long fallback, long baixaConfianca) {
    }

    public record IntentUso(String intent, long vezes, Double confianca) {
    }

    public record Indicadores(
            long mensagens,
            long conversas,
            long fallback,
            long baixaConfianca,
            long transbordos,
            long pendentes,
            long ensinadas,
            List<Dia> porDia,
            List<IntentUso> intents
    ) {
    }

    public record Decisao(String texto, Acao acao, String intent, String frase, String usuario) {
    }

    /**
     * Intent como está no agente. {@code porEvento}: disparada pelo backend (resultado do webhook), sem frase de
     * treino; ensinar frase nela não faz o bot reconhecer nada.
     */
    public record IntentAgente(String nome, List<String> frases, List<String> contextos, boolean fallback,
                               boolean porEvento) {
        public boolean ensinavel() {
            return !fallback && !(porEvento && frases.isEmpty());
        }
    }

    /** Intent explicada para quem faz a curadoria: o que ela faz, frases que já reconhece e quando vale. */
    public record IntentInfo(String nome, String descricao, List<String> exemplos, String momento) {
    }

    /** Intent provável para a fala, pela semelhança com as frases que ela já reconhece (0 a 100). */
    public record Sugestao(String intent, String descricao, int semelhanca, String exemplo, String momento) {
    }
}
