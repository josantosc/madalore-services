package br.com.mandalore.agendamentoconversa.application.domain.model;

public record ItemCatalogo(String id, String nome, boolean usado, String acao) {

    public static ItemCatalogo real(String id, String nome, boolean usado) {
        return new ItemCatalogo(id, nome, usado, null);
    }

    public static ItemCatalogo sintetico(String acao, String nome) {
        return new ItemCatalogo(null, nome, false, acao);
    }

    public boolean sintetico() {
        return acao != null && !acao.isBlank();
    }
}
