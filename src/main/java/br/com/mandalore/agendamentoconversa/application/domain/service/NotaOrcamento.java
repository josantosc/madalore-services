package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.ItemPreco;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.Linha;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.PedidoLido;

/** Rascunho de orçamento para o atendente conferir com a foto antes de enviar. */
final class NotaOrcamento {

    static final String NAO_E_PEDIDO = """
            A imagem enviada não parece um pedido médico de exames. Veja o anexo e responda o paciente.""";
    static final String SEM_EXAMES = """
            Não identifiquei os exames na foto do pedido (foto cortada, tremida ou letra ilegível).
            Veja o anexo ou peça outra foto com /pedido-foto.""";
    static final String FALHA = """
            Não consegui ler a foto do pedido automaticamente. Veja o anexo e monte o orçamento manualmente.""";

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

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

    static String nota(PedidoLido pedido, List<Linha> linhas) {
        if (!pedido.pedidoMedico()) {
            return NAO_E_PEDIDO;
        }
        if (linhas.isEmpty()) {
            return SEM_EXAMES;
        }
        List<Linha> comPreco = linhas.stream().filter(l -> l.item() != null).toList();
        List<Linha> semPreco = linhas.stream().filter(l -> l.item() == null).toList();

        StringBuilder texto = new StringBuilder("Orçamento sugerido pela leitura automática do pedido. ")
                .append("Confira com a foto antes de enviar.\n");
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
            semPreco.forEach(l -> texto.append("- ").append(l.lido()).append('\n'));
        }
        return texto.toString().trim();
    }

    static String reais(BigDecimal valor) {
        return String.format(PT_BR, "R$ %,.2f", valor);
    }
}
