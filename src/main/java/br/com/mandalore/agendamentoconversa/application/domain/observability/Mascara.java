package br.com.mandalore.agendamentoconversa.application.domain.observability;

import java.util.regex.Pattern;

/**
 * Tira dados pessoais da fala do paciente antes de guardá-la para curadoria.
 * Nomes próprios não são detectados; a fala mascarada ainda é dado pessoal (LGPD).
 */
public final class Mascara {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern CPF = Pattern.compile("(?<!\\d)\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?!\\d)");
    private static final Pattern DATA = Pattern.compile("(?<!\\d)\\d{1,2}[/.-]\\d{1,2}(?:[/.-]\\d{2,4})?(?!\\d)");
    private static final Pattern TELEFONE = Pattern.compile(
            "(?<!\\d)(?:\\+?55[\\s-]?)?\\(?\\d{2}\\)?[\\s-]?9?\\d{4}[\\s-]?\\d{4}(?!\\d)");
    private static final Pattern NUMERO_LONGO = Pattern.compile("(?<!\\d)\\d{5,}(?!\\d)");

    private Mascara() {
    }

    public static String texto(String valor) {
        if (valor == null || valor.isBlank()) {
            return "";
        }
        String texto = EMAIL.matcher(valor).replaceAll("<email>");
        texto = CPF.matcher(texto).replaceAll("<cpf>");
        texto = TELEFONE.matcher(texto).replaceAll("<telefone>");
        texto = DATA.matcher(texto).replaceAll("<data>");
        return NUMERO_LONGO.matcher(texto).replaceAll("<numero>");
    }
}
