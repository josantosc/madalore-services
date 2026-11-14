package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.Optional;

/** Configuração do check-in pelo WhatsApp por clínica. */
public interface CheckinConfigPort {

    /** Número do WhatsApp da clínica, só dígitos com DDI (ex.: 5547999990000). */
    Optional<String> whatsapp(String conta);

    /** Segredo que deriva o código do dia; sem ele não há check-in. */
    Optional<String> segredo(String conta);
}
