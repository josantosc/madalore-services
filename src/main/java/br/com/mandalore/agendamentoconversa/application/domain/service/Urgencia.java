package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.List;

/**
 * Reconhece mensagens com sinal de emergência, que não podem seguir no fluxo de agendamento.
 * O texto é comparado sem acento e em minúsculas ({@link Textos#normalizar}).
 */
final class Urgencia {

    private static final List<String> SINAIS = List.of(
            "emergencia", "dor no peito", "falta de ar", "nao consigo respirar", "dificuldade para respirar",
            "desmai", "convuls", "infarto", "derrame", "sangramento forte", "sangrando muito",
            "suicid", "me matar", "overdose", "envenen"
    );

    private Urgencia() {
    }

    static boolean detectar(String texto) {
        String normal = Textos.normalizar(texto);
        return !normal.isBlank() && SINAIS.stream().anyMatch(normal::contains);
    }
}
