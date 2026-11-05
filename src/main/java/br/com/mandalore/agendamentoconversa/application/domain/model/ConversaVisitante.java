package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.util.List;

public record ConversaVisitante(String conversationId, String status, List<Mensagem> mensagens) {

    public ConversaVisitante {
        mensagens = mensagens == null ? List.of() : List.copyOf(mensagens);
    }

    public boolean emAtendimento() {
        return "open".equalsIgnoreCase(status);
    }

    public record Mensagem(long id, String texto, boolean doPaciente, String remetente, String tipoRemetente, List<Anexo> anexos) {

        public Mensagem {
            anexos = anexos == null ? List.of() : List.copyOf(anexos);
        }

        public Mensagem(long id, String texto, boolean doPaciente, String remetente, String tipoRemetente) {
            this(id, texto, doPaciente, remetente, tipoRemetente, List.of());
        }
    }

    /** Arquivo enviado pelo simulador como se fosse o paciente. */
    public record Arquivo(String nome, String tipo, byte[] conteudo) {
    }
}
