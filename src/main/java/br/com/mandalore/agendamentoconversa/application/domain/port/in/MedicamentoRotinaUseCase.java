package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;

/** Lista de medicamentos de rotina que o gestor mantém e os médicos usam ao montar a receita. */
public interface MedicamentoRotinaUseCase {

    /** Na primeira consulta da conta, grava a lista padrão do sistema. */
    List<Atendimento.MedicamentoRotina> listar(String conta);

    Atendimento.Operacao salvar(String conta, Atendimento.MedicamentoRotina item, String ator);

    boolean excluir(String conta, long id);

    /** Inclui de novo os itens da lista padrão que não estão na conta. Devolve quantos entraram. */
    int restaurarPadrao(String conta, String ator);
}
