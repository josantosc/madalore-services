package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.util.Locale;

/** Anexo de mensagem do Chatwoot; {@code tipo} é o file_type (image, file, audio...). */
public record Anexo(String tipo, String url) {

    /** Foto ou PDF, formatos em que um pedido médico costuma chegar. */
    public boolean documento() {
        if (url == null || url.isBlank()) {
            return false;
        }
        if ("image".equalsIgnoreCase(tipo)) {
            return true;
        }
        String caminho = url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        return "file".equalsIgnoreCase(tipo) && caminho.endsWith(".pdf");
    }
}
