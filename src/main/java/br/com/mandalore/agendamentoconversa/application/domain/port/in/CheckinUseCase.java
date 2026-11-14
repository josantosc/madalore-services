package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.util.Optional;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;

/** Check-in do paciente pelo WhatsApp com o código do dia mostrado na recepção. */
public interface CheckinUseCase {

    /**
     * Resposta ao paciente quando a mensagem pertence ao check-in ("Cheguei ABC123" ou os dados pedidos em seguida);
     * vazio para a mensagem seguir o fluxo normal do assistente.
     */
    Optional<String> responder(String conta, String conversa, String texto);

    /** Código e link do dia; vazio quando a clínica não tem WhatsApp e segredo configurados. */
    Optional<Atendimento.CheckinDoDia> doDia(String conta);
}
