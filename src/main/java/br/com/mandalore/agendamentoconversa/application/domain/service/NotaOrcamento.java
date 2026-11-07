package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.ItemPreco;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.Linha;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.PedidoLido;

/** Rascunho de orçamento (foto do pedido ou comando /orcamento) para o atendente conferir antes de enviar. */
final class NotaOrcamento {

    static final String NAO_E_PEDIDO = """
            A imagem enviada não parece um pedido médico de exames. Veja o anexo e responda o paciente.""";
    static final String SEM_EXAMES = """
            Não identifiquei os exames na foto do pedido (foto cortada, tremida ou letra ilegível).
            Veja o anexo ou peça outra foto com /pedido-foto.""";
    static final String FALHA = """
            Não consegui ler a foto do pedido automaticamente. Veja o anexo e monte o orçamento manualmente.""";
    static final String COMANDO_VAZIO = """
            Escreva os exames depois do comando, separados por vírgula. Exemplo: /orcamento hemograma, glicose, TSH""";
    static final String COMANDO_FALHA = """
            Não consegui consultar a tabela de preços agora. Tente o /orcamento de novo em instantes.""";

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    /** Primeiras palavras que não identificam um exame sozinhas ("exame de urina", "sangue oculto"). */
    private static final Set<String> GENERICAS = Set.of(
            "exame", "exames", "dosagem", "pesquisa", "teste", "perfil", "consulta", "coleta", "cultura", "analise",
            "contagem", "sangue", "urina", "fezes", "painel", "avaliacao");

    private NotaOrcamento() {
    }

    static List<Linha> casar(PedidoLido pedido, List<ItemPreco> tabela) {
        Map<String, ItemPreco> porNome = new HashMap<>();
        for (ItemPreco item : tabela) {
            porNome.putIfAbsent(Textos.normalizar(item.nome()), item);
            for (String sinonimo : item.sinonimos()) {
                porNome.putIfAbsent(Textos.normalizar(sinonimo), item);
            }
        }
        List<Linha> linhas = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        pedido.exames().forEach(exame -> {
            ItemPreco item = porNome.get(Textos.normalizar(exame.item()));
            if (item == null) {
                item = porNome.get(Textos.normalizar(exame.lido()));
            }
            String chave = item == null ? "?" + Textos.normalizar(exame.lido()) : item.codigo();
            if (vistos.add(chave)) {
                linhas.add(new Linha(exame.lido(), item));
            }
        });
        return linhas;
    }

    /** Exames do comando /orcamento, separados por vírgula, ponto e vírgula ou quebra de linha. */
    static List<String> termos(String exames) {
        if (exames == null) {
            return List.of();
        }
        return Arrays.stream(exames.split("[,;\\n]+")).map(String::trim).filter(t -> !t.isEmpty()).toList();
    }

    /**
     * Casa o que o atendente digitou por nome, sinônimo ou código; sem igualdade, aceita o único item da tabela que
     * contém o termo ("hemograma" acha "Hemograma completo"). Mais de um candidato fica sem preço, com sugestões.
     */
    static List<Linha> casarTexto(List<String> termos, List<ItemPreco> tabela) {
        Map<String, ItemPreco> porNome = new HashMap<>();
        for (ItemPreco item : tabela) {
            porNome.putIfAbsent(Textos.normalizar(item.codigo()), item);
            porNome.putIfAbsent(Textos.normalizar(item.nome()), item);
            for (String sinonimo : item.sinonimos()) {
                porNome.putIfAbsent(Textos.normalizar(sinonimo), item);
            }
        }
        List<Linha> linhas = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (String termo : termos) {
            ItemPreco item = porNome.get(Textos.normalizar(termo));
            if (item == null) {
                List<ItemPreco> candidatos = parecidos(termo, tabela);
                item = candidatos.size() == 1 ? candidatos.get(0) : null;
            }
            String chave = item == null ? "?" + Textos.normalizar(termo) : item.codigo();
            if (vistos.add(chave)) {
                linhas.add(new Linha(termo, item));
            }
        }
        return linhas;
    }

    /**
     * Exames da tabela citados na frase do paciente, na ordem em que aparecem. Casa nome e sinônimo inteiros (palavra
     * a palavra, sem acento) e, para "hemograma" achar "Hemograma completo", a primeira palavra do nome quando só um
     * item começa com ela. O termo mais longo vence: "T4 livre" não conta também como "T4".
     */
    static List<Linha> examesNaFrase(String frase, List<ItemPreco> tabela) {
        Map<String, Long> primeiras = new HashMap<>();
        tabela.forEach(item -> primeiras.merge(primeiraPalavra(item.nome()), 1L, Long::sum));
        List<Map.Entry<String, ItemPreco>> termos = new ArrayList<>();
        for (ItemPreco item : tabela) {
            termos.add(Map.entry(palavras(item.nome()), item));
            item.sinonimos().forEach(sinonimo -> termos.add(Map.entry(palavras(sinonimo), item)));
            String primeira = primeiraPalavra(item.nome());
            if (primeira.length() >= 5 && !GENERICAS.contains(primeira) && primeiras.get(primeira) == 1) {
                termos.add(Map.entry(primeira, item));
            }
        }
        termos.sort((a, b) -> b.getKey().length() - a.getKey().length());

        StringBuilder texto = new StringBuilder(" ").append(palavras(frase)).append(' ');
        Map<Integer, Linha> achados = new TreeMap<>();
        Set<String> vistos = new HashSet<>();
        for (Map.Entry<String, ItemPreco> termo : termos) {
            if (termo.getKey().length() < 2) {
                continue;
            }
            int posicao = texto.indexOf(" " + termo.getKey() + " ");
            if (posicao < 0) {
                continue;
            }
            texto.replace(posicao + 1, posicao + 1 + termo.getKey().length(), " ".repeat(termo.getKey().length()));
            if (vistos.add(termo.getValue().codigo())) {
                achados.put(posicao, new Linha(termo.getValue().nome(), termo.getValue()));
            }
        }
        return List.copyOf(achados.values());
    }

    private static String palavras(String texto) {
        return Textos.normalizar(texto).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String primeiraPalavra(String nome) {
        String palavras = palavras(nome);
        int espaco = palavras.indexOf(' ');
        return espaco < 0 ? palavras : palavras.substring(0, espaco);
    }

    private static List<ItemPreco> parecidos(String termo, List<ItemPreco> tabela) {
        String normal = Textos.normalizar(termo);
        if (normal.length() < 2) {
            return List.of();
        }
        return tabela.stream()
                .filter(item -> Textos.normalizar(item.nome()).contains(normal)
                        || item.sinonimos().stream().anyMatch(s -> Textos.normalizar(s).contains(normal)))
                .toList();
    }

    static String nota(PedidoLido pedido, List<Linha> linhas) {
        if (!pedido.pedidoMedico()) {
            return NAO_E_PEDIDO;
        }
        if (linhas.isEmpty()) {
            return SEM_EXAMES;
        }
        return montar("Orçamento sugerido pela leitura automática do pedido. Confira com a foto antes de enviar.\n",
                linhas, List.of());
    }

    static String notaComando(List<Linha> linhas, List<ItemPreco> tabela) {
        if (linhas.isEmpty()) {
            return COMANDO_VAZIO;
        }
        String nota = montar("Orçamento calculado pela tabela de preços. Confira antes de enviar.\n", linhas, tabela);
        return linhas.stream().anyMatch(l -> l.item() != null)
                ? nota + "\n\nValor registrado no funil. Para corrigir, mande o /orcamento de novo com a lista certa."
                : nota;
    }

    static String notaTexto(List<Linha> linhas) {
        return montar("Orçamento calculado pelos exames que o paciente escreveu. Confira com a mensagem dele antes "
                + "de enviar; exame que ele citou e não está na tabela não aparece aqui.\n", linhas, List.of());
    }

    private static String montar(String cabecalho, List<Linha> linhas, List<ItemPreco> tabela) {
        List<Linha> comPreco = linhas.stream().filter(l -> l.item() != null).toList();
        List<Linha> semPreco = linhas.stream().filter(l -> l.item() == null).toList();

        StringBuilder texto = new StringBuilder(cabecalho);
        if (!comPreco.isEmpty()) {
            BigDecimal total = BigDecimal.ZERO;
            texto.append("\n--- Texto para o paciente ---\n")
                    .append("Orçamento dos exames do seu pedido (particular):\n");
            for (Linha linha : comPreco) {
                ItemPreco item = linha.item();
                total = total.add(item.valor());
                texto.append("- ").append(item.nome()).append(": ").append(reais(item.valor()));
                if (!item.prazo().isBlank()) {
                    texto.append(" (resultado em ").append(item.prazo()).append(')');
                }
                texto.append('\n');
            }
            texto.append("Total: ").append(reais(total)).append('\n')
                    .append("Valores para pagamento particular. Se for usar convênio, me diga qual é.\n")
                    .append("---\n");
        }
        if (!semPreco.isEmpty()) {
            texto.append("\nSem preço na tabela (verificar):\n");
            semPreco.forEach(l -> {
                texto.append("- ").append(l.lido());
                List<String> sugestoes = parecidos(l.lido(), tabela).stream().limit(3).map(ItemPreco::nome).toList();
                if (!sugestoes.isEmpty()) {
                    texto.append(" (na tabela: ").append(String.join("; ", sugestoes)).append(')');
                }
                texto.append('\n');
            });
        }
        return texto.toString().trim();
    }

    static String reais(BigDecimal valor) {
        return String.format(PT_BR, "R$ %,.2f", valor);
    }
}
