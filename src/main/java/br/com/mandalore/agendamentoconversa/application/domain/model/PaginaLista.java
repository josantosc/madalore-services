package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.util.List;

public record PaginaLista(String tipo, int offset, List<ItemCatalogo> todos, List<ItemCatalogo> exibidos) {
}
