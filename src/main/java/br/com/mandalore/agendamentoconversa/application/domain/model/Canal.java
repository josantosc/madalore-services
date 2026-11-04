package br.com.mandalore.agendamentoconversa.application.domain.model;

public enum Canal {
    WHATSAPP(1500, 0),
    // Instagram recusa texto acima de 1000 bytes UTF-8; acento ocupa 2 bytes e emoji 4.
    INSTAGRAM(900, 1000),
    MESSENGER(2000, 0),
    SITE(1500, 0),
    OUTRO(1500, 0);

    private final int limiteCaracteres;
    private final int limiteBytes;

    Canal(int limiteCaracteres, int limiteBytes) {
        this.limiteCaracteres = limiteCaracteres;
        this.limiteBytes = limiteBytes;
    }

    public static Canal doChatwoot(String channel, String tipoConversa) {
        if ("instagram_direct_message".equalsIgnoreCase(tipoConversa)) {
            return INSTAGRAM;
        }
        if (channel == null) {
            return OUTRO;
        }
        return switch (channel) {
            case "Channel::Whatsapp" -> WHATSAPP;
            case "Channel::Instagram" -> INSTAGRAM;
            case "Channel::FacebookPage" -> MESSENGER;
            case "Channel::WebWidget" -> SITE;
            default -> OUTRO;
        };
    }

    public int limiteCaracteres() {
        return limiteCaracteres;
    }

    public int limiteBytes() {
        return limiteBytes;
    }
}
