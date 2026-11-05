package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.ItemPreco;

/** Regras de um item da tabela de preços, iguais para a tela e para a importação do CSV. */
final class ValidacaoPreco {

    private static final Pattern CODIGO = Pattern.compile("[A-Za-z0-9._-]{1,60}");
    private static final BigDecimal MAXIMO = new BigDecimal("99999999.99");

    private ValidacaoPreco() {
    }

    /** Devolve o item normalizado (espaços aparados, valor com 2 casas) ou lança IllegalArgumentException. */
    static ItemPreco validar(ItemPreco item) {
        String codigo = aparar(item.codigo());
        String nome = aparar(item.nome());
        String prazo = aparar(item.prazo());
        List<String> sinonimos = (item.sinonimos() == null ? List.<String>of() : item.sinonimos()).stream()
                .map(ValidacaoPreco::aparar)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (!CODIGO.matcher(codigo).matches()) {
            throw new IllegalArgumentException(
                    "Código obrigatório, até 60 caracteres, só letras, números, ponto, hífen e sublinhado");
        }
        if (nome.isEmpty() || nome.length() > 200) {
            throw new IllegalArgumentException("Nome obrigatório, até 200 caracteres");
        }
        if (sinonimos.stream().anyMatch(s -> s.contains("|")) || String.join("|", sinonimos).length() > 1000) {
            throw new IllegalArgumentException("Sinônimos sem \"|\", até 1000 caracteres no total");
        }
        if (prazo.length() > 100) {
            throw new IllegalArgumentException("Prazo até 100 caracteres");
        }
        BigDecimal valor = item.valor();
        if (valor == null || valor.signum() < 0 || valor.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException("Valor obrigatório, entre 0 e 99.999.999,99");
        }
        return new ItemPreco(codigo, nome, sinonimos, valor.setScale(2, RoundingMode.HALF_UP), prazo);
    }

    private static String aparar(String valor) {
        return valor == null ? "" : valor.replaceAll("\\p{Cntrl}", " ").trim();
    }
}
