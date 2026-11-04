package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Textos {

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DIA_CURTO = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DIA_RESUMO =
            DateTimeFormatter.ofPattern("dd/MM (EEEE)", Locale.forLanguageTag("pt-BR"));
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern SESSAO = Pattern.compile("cw-(\\d+)-(\\d+)$");

    private Textos() {
    }

    public static String normalizar(String valor) {
        if (valor == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(valor, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).trim();
    }

    public static List<String> partes(String texto, int limite) {
        List<String> partes = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return partes;
        }
        StringBuilder atual = new StringBuilder();
        for (String paragrafo : texto.trim().split("\n\n")) {
            for (String pedaco : cortar(paragrafo, limite)) {
                if (atual.length() > 0 && atual.length() + 2 + pedaco.length() > limite) {
                    partes.add(atual.toString());
                    atual.setLength(0);
                }
                if (atual.length() > 0) {
                    atual.append("\n\n");
                }
                atual.append(pedaco);
            }
        }
        if (atual.length() > 0) {
            partes.add(atual.toString());
        }
        return partes;
    }

    public static List<String> partes(String texto, int limite, int limiteBytes) {
        List<String> partes = partes(texto, limite);
        while (limiteBytes > 0 && limite > 100 && partes.stream().anyMatch(p -> bytes(p) > limiteBytes)) {
            limite -= 100;
            partes = partes(texto, limite);
        }
        return partes;
    }

    private static int bytes(String valor) {
        return valor.getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<String> cortar(String paragrafo, int limite) {
        List<String> pedacos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        for (String linha : paragrafo.split("\n")) {
            while (linha.length() > limite) {
                if (atual.length() > 0) {
                    pedacos.add(atual.toString());
                    atual.setLength(0);
                }
                pedacos.add(linha.substring(0, limite));
                linha = linha.substring(limite);
            }
            if (atual.length() > 0 && atual.length() + 1 + linha.length() > limite) {
                pedacos.add(atual.toString());
                atual.setLength(0);
            }
            if (atual.length() > 0) {
                atual.append('\n');
            }
            atual.append(linha);
        }
        if (atual.length() > 0) {
            pedacos.add(atual.toString());
        }
        return pedacos;
    }

    public static String primeiroNome(String nome) {
        if (nome == null || nome.isBlank()) {
            return "";
        }
        return nome.trim().split("\\s+")[0];
    }

    public static String digitosCpf(Object valor) {
        if (valor == null) {
            return "";
        }
        return valor.toString().replaceAll("\\D", "");
    }

    public static String texto(Map<String, Object> parametros, String chave) {
        if (parametros == null || parametros.get(chave) == null) {
            return "";
        }
        Object valor = parametros.get(chave);
        if (valor instanceof Map<?, ?> mapa) {
            Object interno = mapa.get(chave);
            if (interno == null) {
                interno = mapa.get("name");
            }
            return interno == null ? "" : interno.toString().trim();
        }
        return valor.toString().trim();
    }

    public static Integer numero(Map<String, Object> parametros, String chave) {
        String bruto = texto(parametros, chave);
        if (bruto.isBlank()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(bruto.replace(",", "."));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static LocalDate data(Object valor) {
        if (valor == null) {
            return null;
        }
        String bruto = valor.toString().trim();
        if (bruto.isBlank()) {
            return null;
        }
        try {
            if (bruto.length() >= 10 && bruto.charAt(4) == '-') {
                return LocalDate.parse(bruto.substring(0, 10));
            }
        } catch (DateTimeParseException ignored) {
            return null;
        }
        String[] partes = bruto.split("[/\\-.]");
        if (partes.length < 3) {
            return null;
        }
        try {
            int a = Integer.parseInt(partes[0]);
            int b = Integer.parseInt(partes[1]);
            int ano = Integer.parseInt(partes[2].substring(0, Math.min(4, partes[2].length())));
            if (ano < 100) {
                ano += 1900;
            }
            if (a > 12 && b <= 12) {
                return LocalDate.of(ano, b, a);
            }
            if (b > 12 && a <= 12) {
                return LocalDate.of(ano, a, b);
            }
            if (a <= 12 && b <= 12) {
                return LocalDate.of(ano, b, a);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    public static String dia(LocalDateTime dataHora) {
        return dataHora == null ? "" : DIA.format(dataHora);
    }

    public static String diaCurto(LocalDateTime dataHora) {
        return dataHora == null ? "" : DIA_CURTO.format(dataHora);
    }

    public static String diaResumo(LocalDateTime dataHora) {
        return dataHora == null ? "" : DIA_RESUMO.format(dataHora);
    }

    public static String hora(LocalDateTime dataHora) {
        return dataHora == null ? "" : HORA.format(dataHora);
    }

    public static String periodo(String valor) {
        String normal = normalizar(valor);
        if (normal.isBlank()) {
            return "";
        }
        if (normal.equals("1") || normal.contains("manha") || normal.equals("cedo")) {
            return "manha";
        }
        if (normal.equals("2") || normal.contains("tarde")) {
            return "tarde";
        }
        if (normal.equals("3") || normal.contains("tanto") || normal.contains("qualquer") || normal.contains("indiferente")) {
            return "tanto_faz";
        }
        return "";
    }

    public static String periodoFrase(String periodo) {
        return switch (periodo == null ? "" : periodo) {
            case "manha" -> "de manhã";
            case "tarde" -> "à tarde";
            default -> "em qualquer período";
        };
    }

    public static boolean sim(String valor) {
        String normal = normalizar(valor);
        return normal.equals("1")
                || normal.equals("sim")
                || normal.equals("isso")
                || normal.equals("pode")
                || normal.equals("quero")
                || normal.equals("confirmo")
                || normal.contains("pode marcar");
    }

    public static boolean nao(String valor) {
        String normal = normalizar(valor);
        return normal.equals("2")
                || normal.equals("nao")
                || normal.contains("nao quero")
                || normal.contains("outro horario")
                || normal.equals("troca");
    }

    public static boolean semana(String texto) {
        String normal = normalizar(texto);
        return normal.contains("esta semana") || normal.contains("essa semana");
    }

    public static String conversationId(String session) {
        if (session == null) {
            return "";
        }
        Matcher matcher = SESSAO.matcher(session);
        if (matcher.find()) {
            return matcher.group(2);
        }
        int barra = session.lastIndexOf('/');
        return barra < 0 ? session : session.substring(barra + 1);
    }

    public static String accountId(String session) {
        if (session == null) {
            return null;
        }
        Matcher matcher = SESSAO.matcher(session);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String sessionId(String accountId, String conversationId) {
        String id = "cw-" + tokenSessao(accountId) + "-" + tokenSessao(conversationId);
        if (id.length() <= 36) {
            return id;
        }
        String hash = sha256(tokenSessao(accountId) + ":" + tokenSessao(conversationId));
        return "cw" + hash.substring(0, 34);
    }

    private static String tokenSessao(String valor) {
        if (valor == null) {
            return "0";
        }
        String limpo = valor.replaceAll("[^A-Za-z0-9_-]", "");
        return limpo.isBlank() ? "0" : limpo;
    }

    private static String sha256(String valor) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao montar a sessão do Dialogflow", ex);
        }
    }
}
