package br.com.mandalore.agendamentoconversa.adapter.cliente;

import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciais por conta do Chatwoot (um hospital ou clínica por conta).
 * A chave do mapa é o {@code account.id}. Conta sem cadastro usa os valores globais do {@code .env}.
 */
@ConfigurationProperties("mandalore")
public record ClientesProperties(Map<String, Cliente> clientes) {

    public ClientesProperties {
        clientes = clientes == null ? Map.of() : Map.copyOf(clientes);
    }

    public Optional<Cliente> cliente(String accountId) {
        return accountId == null ? Optional.empty() : Optional.ofNullable(clientes.get(accountId));
    }

    public record Cliente(String chatwootToken, String webhookSecret, Tasy tasy) {
    }

    public record Tasy(String url, String username, String password) {

        public boolean configurado() {
            return url != null && !url.isBlank();
        }
    }
}
