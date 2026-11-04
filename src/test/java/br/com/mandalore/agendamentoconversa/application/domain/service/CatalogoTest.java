package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PaginaLista;

class CatalogoTest {

    private static List<String> nomes(PaginaLista pagina) {
        return pagina.exibidos().stream().map(ItemCatalogo::nome).toList();
    }

    @Test
    void verOutrosConveniosMostraOsQueNaoApareceram() {
        PaginaLista primeira = Catalogo.primeira("convenio", List.of(
                ItemCatalogo.real("15", "Convênio Teste", false),
                ItemCatalogo.real("1", "Particular", false)
        ));

        assertThat(nomes(primeira)).containsExactly("Particular", "Ver outros");
        assertThat(nomes(Catalogo.proxima(primeira))).containsExactly("Convênio Teste");
    }

    @Test
    void verOutrosConveniosNaoRepeteUsadoNemParticular() {
        PaginaLista primeira = Catalogo.primeira("convenio", List.of(
                ItemCatalogo.real("2", "Amil", false),
                ItemCatalogo.real("1", "Particular", false),
                ItemCatalogo.real("3", "Unimed", true),
                ItemCatalogo.real("4", "Bradesco", false)
        ));

        assertThat(nomes(primeira)).containsExactly("Unimed", "Particular", "Ver outros");
        assertThat(nomes(Catalogo.proxima(primeira))).containsExactly("Amil", "Bradesco");
    }

    @Test
    void escolhaPorNomeContinuaEncontrandoTodos() {
        PaginaLista primeira = Catalogo.primeira("convenio", List.of(
                ItemCatalogo.real("15", "Convênio Teste", false),
                ItemCatalogo.real("1", "Particular", false)
        ));

        assertThat(Catalogo.buscarNome(primeira, "convenio teste"))
                .extracting(ItemCatalogo::id)
                .containsExactly("15");
    }

    @Test
    void listaSimplesPaginaDeOitoEmOito() {
        List<ItemCatalogo> itens = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> ItemCatalogo.real(String.valueOf(i), "Item " + i, false))
                .toList();

        PaginaLista primeira = Catalogo.primeira("especialidade", itens);

        assertThat(primeira.exibidos()).hasSize(9);
        assertThat(primeira.exibidos().get(8).acao()).isEqualTo("ver_outros");
        assertThat(nomes(Catalogo.proxima(primeira))).containsExactly("Item 9", "Item 10");
    }
}
