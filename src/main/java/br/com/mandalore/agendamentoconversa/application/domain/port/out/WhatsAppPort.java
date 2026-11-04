package br.com.mandalore.agendamentoconversa.application.domain.port.out;

public interface WhatsAppPort {

    String enviar(String destinatario, String texto);
}
