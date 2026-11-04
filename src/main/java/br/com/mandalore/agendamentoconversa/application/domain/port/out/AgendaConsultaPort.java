package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PacienteCadastro;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;

public interface AgendaConsultaPort {

    List<PacienteCadastro> identificar(String cpfDigitos, LocalDate nascimento);

    List<AgendamentoAberto> agendamentosFuturos(String pacienteId);

    List<ItemCatalogo> convenios(String pacienteId);

    List<ItemCatalogo> profissionais(String pacienteId);

    List<ItemCatalogo> especialidades(String medicoId);

    List<HorarioOferta> disponibilidade(FiltroDisponibilidade filtro);

    List<ItemCatalogo> exames();

    List<HorarioOferta> disponibilidadeExame(String exameId, LocalDateTime inicio, LocalDateTime fim, String periodo);

    ProcedimentoExame procedimentoExame(String exameId);

    String orientacaoExame(String exameId);
}
