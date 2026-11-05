package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento.ItemPreco;

/**
 * Tabela de preços exportada de planilha: {@code codigo;nome;sinonimos;valor;prazo}, com cabeçalho.
 * Aceita ";" ou ",", valor "1.250,00" ou "1250.00", e arquivo UTF-8 ou Windows-1252 (Excel).
 * Sinônimos separados por "|".
 */
final class TabelaPrecoCsv {

    private static final List<String> COLUNAS = List.of("codigo", "nome", "sinonimos", "valor", "prazo");

    private TabelaPrecoCsv() {
    }

    static List<ItemPreco> ler(byte[] conteudo) {
        String texto = decodificar(conteudo).replace("\uFEFF", "");
        List<String> linhas = texto.lines().filter(l -> !l.isBlank()).toList();
        if (linhas.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio");
        }
        char separador = linhas.get(0).contains(";") ? ';' : ',';
        List<String> cabecalho = campos(linhas.get(0), separador).stream().map(Textos::normalizar).toList();
        if (!cabecalho.containsAll(COLUNAS.subList(0, 2)) || !cabecalho.contains("valor")) {
            throw new IllegalArgumentException("Cabeçalho deve ter as colunas " + String.join(";", COLUNAS));
        }
        List<ItemPreco> itens = new ArrayList<>();
        Set<String> codigos = new HashSet<>();
        for (int i = 1; i < linhas.size(); i++) {
            int numero = i + 1;
            List<String> campos = campos(linhas.get(i), separador);
            String codigo = campo(campos, cabecalho, "codigo");
            String nome = campo(campos, cabecalho, "nome");
            if (codigo.isBlank() || nome.isBlank()) {
                throw new IllegalArgumentException("Linha " + numero + ": código e nome são obrigatórios");
            }
            if (!codigos.add(codigo)) {
                throw new IllegalArgumentException("Linha " + numero + ": código " + codigo + " repetido");
            }
            List<String> sinonimos = Arrays.stream(campo(campos, cabecalho, "sinonimos").split("\\|"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            String prazo = campo(campos, cabecalho, "prazo");
            if (codigo.length() > 60 || nome.length() > 200 || String.join("|", sinonimos).length() > 1000
                    || prazo.length() > 100) {
                throw new IllegalArgumentException("Linha " + numero
                        + ": texto longo demais (código 60, nome 200, sinônimos 1000, prazo 100 caracteres)");
            }
            itens.add(new ItemPreco(codigo, nome, sinonimos, valor(campo(campos, cabecalho, "valor"), numero), prazo));
        }
        return itens;
    }

    static BigDecimal valor(String bruto, int linha) {
        String limpo = bruto.replace("R$", "").replace("\u00A0", "").replace(" ", "");
        if (limpo.contains(",")) {
            limpo = limpo.replace(".", "").replace(',', '.');
        }
        try {
            BigDecimal valor = new BigDecimal(limpo);
            if (valor.signum() < 0) {
                throw new NumberFormatException();
            }
            return valor.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Linha " + linha + ": valor inválido \"" + bruto + "\"");
        }
    }

    private static String campo(List<String> campos, List<String> cabecalho, String coluna) {
        int indice = cabecalho.indexOf(coluna);
        return indice < 0 || indice >= campos.size() ? "" : campos.get(indice).trim();
    }

    private static List<String> campos(String linha, char separador) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean aspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                if (aspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"');
                    i++;
                } else {
                    aspas = !aspas;
                }
            } else if (c == separador && !aspas) {
                campos.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString());
        return campos;
    }

    private static String decodificar(byte[] conteudo) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(conteudo))
                    .toString();
        } catch (CharacterCodingException ex) {
            return new String(conteudo, Charset.forName("windows-1252"));
        }
    }
}
