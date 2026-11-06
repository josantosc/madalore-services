package br.com.mandalore.agendamentoconversa.adapter.out.chatwoot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;

class ChatwootClientAdapterTest {

    @Test
    void baixaAnexoPeloEnderecoInternoDoChatwoot() {
        ChatwootClientAdapter adapter = adapter("http://chatwoot-rails:3000/");

        assertThat(adapter.noChatwoot("https://bs-chat.jscinfor.com/rails/active_storage/blobs/redirect/eyJ--abc/pedido.jpg?disposition=inline"))
                .hasToString("http://chatwoot-rails:3000/rails/active_storage/blobs/redirect/eyJ--abc/pedido.jpg?disposition=inline");
    }

    @Test
    void semBaseUsaOEnderecoDoWebhook() {
        assertThat(adapter("").noChatwoot("https://chat/x.jpg")).hasToString("https://chat/x.jpg");
    }

    private static ChatwootClientAdapter adapter(String base) {
        return new ChatwootClientAdapter(base, "tk", 5000, "", "", "", "", new ClientesProperties(Map.of()));
    }
}
