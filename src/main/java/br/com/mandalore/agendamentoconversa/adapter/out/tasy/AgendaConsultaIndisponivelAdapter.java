package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.cliente.TasyConfigurado;
import br.com.mandalore.agendamentoconversa.application.domain.exception.ConsultaIndisponivelException;
import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PacienteCadastro;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;

@Component
@Conditional(TasyConfigurado.Ausente.class)
public class AgendaConsultaIndisponivelAdapter implements AgendaConsultaPort {

    @Override
    public List<PacienteCadastro> identificar(String cpfDigitos, LocalDate nascimento) {
        throw indisponivel();
    }

    @Override
    public List<AgendamentoAberto> agendamentosFuturos(String pacienteId) {
        throw indisponivel();
    }

    @Override
    public List<ItemCatalogo> convenios(String pacienteId) {
        throw indisponivel();
    }

    @Override
    public List<ItemCatalogo> profissionais(String pacienteId) {
        throw indisponivel();
    }

    @Override
    public List<ItemCatalogo> especialidades(String medicoId) {
        throw indisponivel();
    }

    @Override
    public List<HorarioOferta> disponibilidade(FiltroDisponibilidade filtro) {
        throw indisponivel();
    }

    @Override
    public List<ItemCatalogo> exames() {
        throw indisponivel();
    }

    @Override
    public List<HorarioOferta> disponibilidadeExame(
            String exameId,
            LocalDateTime inicio,
            LocalDateTime fim,
            String periodo
    ) {
        throw indisponivel();
    }

    @Override
    public ProcedimentoExame procedimentoExame(String exameId) {
        throw indisponivel();
    }

    @Override
    public String orientacaoExame(String exameId) {
        throw indisponivel();
    }

    private ConsultaIndisponivelException indisponivel() {
        return new ConsultaIndisponivelException("Oracle não configurado");
    }
}
