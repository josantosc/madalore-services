package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;

/**
 * Escolhe o setor que recebe a conversa quando o bot passa para um atendente.
 * O texto já chega sem acento e em minúsculas ({@link Textos#normalizar}).
 */
final class Encaminhamento {

    private static final Map<Setor, List<String>> PALAVRAS = Map.of(
            Setor.FATURAMENTO, List.of(
                    "convenio", "guia", "autoriza", "carteirinha", "reembolso", "nota fiscal",
                    "boleto", "pagamento", "pagar", "cobranca", "fatura", "glosa", "orcamento"
            ),
            Setor.LABORATORIO, List.of(
                    "resultado", "laudo", "coleta", "coletar", "recoleta", "amostra",
                    "jejum", "preparo", "urina", "fezes", "sangue"
            )
    );

    private Encaminhamento() {
    }

    static Optional<Setor> assunto(String texto) {
        String normal = Textos.normalizar(texto);
        if (normal.isBlank()) {
            return Optional.empty();
        }
        for (Setor setor : List.of(Setor.FATURAMENTO, Setor.LABORATORIO)) {
            if (PALAVRAS.get(setor).stream().anyMatch(normal::contains)) {
                return Optional.of(setor);
            }
        }
        return Optional.empty();
    }
}
