package br.com.mandalore.agendamentoconversa.application.domain.model;

public record MensagemWhatsApp(
        String messageSid,
        String remetente,
        String destinatario,
        String texto
) {
    public boolean valida() {
        return messageSid != null && !messageSid.isBlank()
                && remetente != null && remetente.startsWith("whatsapp:+")
                && destinatario != null && destinatario.startsWith("whatsapp:+");
    }
}
