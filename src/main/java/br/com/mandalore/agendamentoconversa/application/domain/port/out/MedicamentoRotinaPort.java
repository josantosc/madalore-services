package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;

/** Medicamentos de rotina da clínica ({@code atendimento.medicamento_rotina}). */
public interface MedicamentoRotinaPort {

    /**
     * Grava a lista padrão só na primeira vez da conta, mesmo que depois a clínica exclua todos os itens. Devolve true
     * quando gravou agora.
     */
    boolean semearSeNova(String conta, List<Atendimento.MedicamentoRotina> padrao);

    /** Inclui os itens cujo medicamento e apresentação ainda não estão na conta. Devolve quantos entraram. */
    int incluirFaltantes(String conta, List<Atendimento.MedicamentoRotina> itens, String ator);

    List<Atendimento.MedicamentoRotina> listar(String conta);

    /** id nulo cria; id de outra conta: NAO_ENCONTRADO; medicamento e apresentação repetidos: MEDICAMENTO_DUPLICADO. */
    Atendimento.Operacao salvar(String conta, Atendimento.MedicamentoRotina item, String ator);

    boolean excluir(String conta, long id);
}
