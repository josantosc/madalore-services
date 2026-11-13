package br.com.mandalore.agendamentoconversa.application.domain.port.out;

/**
 * Avisa as telas abertas da clínica (recepção e consultório) que a fila mudou, para recarregarem na hora.
 * O aviso leva só o tipo e os números; nunca nome de paciente nem conteúdo clínico.
 */
public interface AvisoFilaPort {

    /** tipo: CHEGADA, CHEGADA_DESFEITA, CHAMADO, FALTOU, FALTA_DESFEITA, ENCAIXE, CONSULTA_INICIADA, FINALIZADO. */
    record Aviso(String tipo, Long agendamentoId, Long atendimentoId) {
    }

    AvisoFilaPort NENHUM = (conta, aviso) -> { };

    void publicar(String conta, Aviso aviso);
}
