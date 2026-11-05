package br.com.mandalore.agendamentoconversa.application.domain.port.out;

public interface NotificacaoPort {

    /** Mensagem de texto para a equipe interna (não para paciente). */
    void enviar(String texto);
}
