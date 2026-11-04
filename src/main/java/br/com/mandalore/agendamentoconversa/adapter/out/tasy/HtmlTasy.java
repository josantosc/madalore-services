package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HtmlTasy {

    private static final Pattern QUEBRA = Pattern.compile(
            "(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>|</tr>|</ol>|</ul>");
    private static final char PARAGRAFO = '\u2029';
    private static final Pattern TITULO = Pattern.compile("(?i)<h[1-6][^>]*>");
    private static final Pattern ITEM = Pattern.compile("(?i)<li[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTIDADE = Pattern.compile("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Map<String, String> ENTIDADES = Map.of(
            "nbsp", " ",
            "amp", "&",
            "lt", "<",
            "gt", ">",
            "quot", "\"",
            "apos", "'"
    );

    private HtmlTasy() {
    }

    static String texto(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String texto = html.replace("\r", "");
        texto = TITULO.matcher(texto).replaceAll("\n" + PARAGRAFO);
        texto = ITEM.matcher(texto).replaceAll("\n• ");
        texto = QUEBRA.matcher(texto).replaceAll("\n");
        texto = TAG.matcher(texto).replaceAll("");
        texto = entidades(texto);
        StringBuilder saida = new StringBuilder();
        boolean novoParagrafo = false;
        for (String linha : texto.split("\n")) {
            if (linha.indexOf(PARAGRAFO) >= 0) {
                novoParagrafo = true;
                linha = linha.replace(String.valueOf(PARAGRAFO), "");
            }
            String limpa = linha.replaceAll("[ \\t\\u00A0]+", " ").trim();
            if (limpa.isEmpty()) {
                continue;
            }
            if (saida.length() > 0) {
                saida.append(novoParagrafo ? "\n\n" : "\n");
            }
            saida.append(limpa);
            novoParagrafo = false;
        }
        return saida.toString();
    }

    private static String entidades(String texto) {
        Matcher matcher = ENTIDADE.matcher(texto);
        StringBuilder saida = new StringBuilder();
        while (matcher.find()) {
            String nome = matcher.group(1);
            String valor;
            if (nome.startsWith("#x") || nome.startsWith("#X")) {
                valor = new String(Character.toChars(Integer.parseInt(nome.substring(2), 16)));
            } else if (nome.startsWith("#")) {
                valor = new String(Character.toChars(Integer.parseInt(nome.substring(1))));
            } else {
                valor = ENTIDADES.getOrDefault(nome.toLowerCase(), matcher.group());
            }
            matcher.appendReplacement(saida, Matcher.quoteReplacement(valor));
        }
        matcher.appendTail(saida);
        return saida.toString();
    }
}
