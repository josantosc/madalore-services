package br.com.mandalore.agendamentoconversa.adapter.cliente;

import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Respostas das perguntas frequentes, por tema: {@code mandalore.faq.<tema>} vale para todas as contas e
 * {@code mandalore.clientes.<account_id>.faq.<tema>} substitui o tema naquela conta.
 */
@ConfigurationProperties("mandalore")
public record PerguntasFrequentesProperties(Map<String, String> faq, Map<String, Cliente> clientes) {

    public PerguntasFrequentesProperties {
        faq = faq == null ? Map.of() : Map.copyOf(faq);
        clientes = clientes == null ? Map.of() : Map.copyOf(clientes);
    }

    public Optional<String> resposta(String accountId, String tema) {
        String daConta = Optional.ofNullable(accountId)
                .map(clientes::get)
                .map(Cliente::faq)
                .map(respostas -> respostas.get(tema))
                .filter(texto -> !texto.isBlank())
                .orElse(null);
        if (daConta != null) {
            return Optional.of(daConta);
        }
        return Optional.ofNullable(faq.get(tema)).filter(texto -> !texto.isBlank());
    }

    public record Cliente(Map<String, String> faq) {
    }
}
