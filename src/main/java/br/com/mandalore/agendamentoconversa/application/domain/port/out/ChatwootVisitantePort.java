package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;

public interface ChatwootVisitantePort {

    String criarVisitante();

    void enviar(String tokenVisitante, String texto);

    ConversaVisitante ler(String tokenVisitante);
}
