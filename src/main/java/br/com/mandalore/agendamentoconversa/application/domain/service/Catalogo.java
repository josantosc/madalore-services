package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import br.com.mandalore.agendamentoconversa.application.domain.model.FulfillmentCommand;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PaginaLista;

public final class Catalogo {

    private static final int TAMANHO = 8;

    private Catalogo() {
    }

    public static PaginaLista primeira(String tipo, List<ItemCatalogo> todos) {
        List<ItemCatalogo> reais = reais(todos);
        if ("convenio".equals(tipo)) {
            return convenio(reais, 0);
        }
        return simples(tipo, reais, 0);
    }

    public static PaginaLista proxima(PaginaLista atual) {
        int mostrados = (int) atual.exibidos().stream().filter(item -> !item.sintetico()).count();
        int offset = atual.offset() + mostrados;
        if ("convenio".equals(atual.tipo())) {
            return convenio(atual.todos(), offset);
        }
        return simples(atual.tipo(), atual.todos(), offset);
    }

    public static ItemCatalogo resolver(PaginaLista pagina, FulfillmentCommand command) {
        Integer numero = Textos.numero(command.parameters(), "escolha");
        if (numero == null) {
            numero = Textos.numero(Map.of("n", command.queryText() == null ? "" : command.queryText()), "n");
        }
        if (numero != null && numero >= 1 && numero <= pagina.exibidos().size()) {
            return pagina.exibidos().get(numero - 1);
        }
        String fala = Textos.normalizar(command.queryText());
        if (fala.contains("ver outros")) {
            return ItemCatalogo.sintetico("ver_outros", "Ver outros");
        }
        if (fala.contains("ver mais")) {
            return ItemCatalogo.sintetico("ver_mais", "Ver mais");
        }
        if (fala.contains("outra pessoa") || fala.equals("outro")) {
            return ItemCatalogo.sintetico("outra_pessoa", "Outra pessoa");
        }
        List<ItemCatalogo> nomes = buscarNome(pagina, fala);
        if (nomes.size() == 1) {
            return nomes.get(0);
        }
        return null;
    }

    public static List<ItemCatalogo> buscarNome(PaginaLista pagina, String fala) {
        if (fala == null || fala.isBlank()) {
            return List.of();
        }
        String normal = Textos.normalizar(fala);
        return pagina.todos().stream()
                .filter(item -> !item.sintetico())
                .filter(item -> {
                    String nome = Textos.normalizar(item.nome());
                    return nome.equals(normal) || nome.contains(normal) || normal.contains(nome);
                })
                .toList();
    }

    private static PaginaLista convenio(List<ItemCatalogo> reais, int offset) {
        if (offset <= 0) {
            List<ItemCatalogo> exibidos = new ArrayList<>();
            reais.stream().filter(ItemCatalogo::usado).findFirst().ifPresent(exibidos::add);
            reais.stream()
                    .filter(item -> Textos.normalizar(item.nome()).contains("particular"))
                    .filter(item -> exibidos.stream().noneMatch(ja -> ja.id().equals(item.id())))
                    .findFirst()
                    .ifPresent(exibidos::add);
            if (exibidos.isEmpty()) {
                exibidos.addAll(reais.stream().limit(TAMANHO).toList());
            }
            List<ItemCatalogo> ordenados = new ArrayList<>(exibidos);
            reais.stream().filter(item -> !exibidos.contains(item)).forEach(ordenados::add);
            if (reais.size() > exibidos.size()) {
                exibidos.add(ItemCatalogo.sintetico("ver_outros", "Ver outros"));
            }
            return new PaginaLista("convenio", 0, ordenados, exibidos);
        }
        return simples("convenio", reais, offset);
    }

    private static PaginaLista simples(String tipo, List<ItemCatalogo> reais, int offset) {
        int inicio = Math.min(Math.max(offset, 0), reais.size());
        List<ItemCatalogo> fatia = new ArrayList<>(reais.subList(inicio, reais.size()).stream().limit(TAMANHO).toList());
        int restante = reais.size() - inicio - fatia.size();
        if (restante > 0) {
            fatia.add(ItemCatalogo.sintetico(offset == 0 ? "ver_outros" : "ver_mais", offset == 0 ? "Ver outros" : "Ver mais"));
        }
        return new PaginaLista(tipo, inicio, reais, fatia);
    }

    private static List<ItemCatalogo> reais(List<ItemCatalogo> todos) {
        return todos.stream().filter(item -> !item.sintetico()).toList();
    }
}
