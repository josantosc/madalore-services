package br.com.mandalore.agendamentoconversa.regression;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PacienteCadastro;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;

final class FakeAgendaBackend implements AgendaConsultaPort, AgendamentoEscritaPort {

    private boolean pacienteCadastrado;
    private boolean possuiAgendamento;
    private final Set<String> periodosSemHorario = new HashSet<>();
    private final List<String> escritas = new ArrayList<>();

    void configurar(JsonNode fixture) {
        pacienteCadastrado = fixture.path("pacienteCadastrado").asBoolean(false);
        possuiAgendamento = fixture.path("possuiAgendamento").asBoolean(false);
        periodosSemHorario.clear();
        fixture.path("periodosSemHorario").forEach(item -> periodosSemHorario.add(item.asText()));
        escritas.clear();
    }

    int quantidadeEscritas() {
        return escritas.size();
    }

    List<String> escritas() {
        return List.copyOf(escritas);
    }

    @Override
    public List<PacienteCadastro> identificar(String cpfDigitos, LocalDate nascimento) {
        return pacienteCadastrado
                ? List.of(new PacienteCadastro("1001", "Maria da Silva"))
                : List.of();
    }

    @Override
    public List<AgendamentoAberto> agendamentosFuturos(String pacienteId) {
        if (!possuiAgendamento) {
            return List.of();
        }
        return List.of(new AgendamentoAberto(
                "7001",
                LocalDateTime.now().plusDays(10).withHour(9).withMinute(0).withSecond(0).withNano(0),
                "Cardiologia",
                "Dra. Ana Teste",
                "Consultório 1",
                "A"
        ));
    }

    @Override
    public List<ItemCatalogo> convenios(String pacienteId) {
        return List.of(
                ItemCatalogo.real("2", "Unimed", false),
                ItemCatalogo.real("1", "Particular", false)
        );
    }

    @Override
    public List<ItemCatalogo> profissionais(String pacienteId) {
        return List.of(
                ItemCatalogo.real("201", "Dra. Ana Teste", false),
                ItemCatalogo.real("202", "Dr. Bruno Teste", false)
        );
    }

    @Override
    public List<ItemCatalogo> especialidades(String medicoId) {
        return List.of(
                ItemCatalogo.real("301", "Cardiologia", false),
                ItemCatalogo.real("302", "Clínica Médica", false)
        );
    }

    @Override
    public List<HorarioOferta> disponibilidade(FiltroDisponibilidade filtro) {
        if (periodosSemHorario.contains(filtro.periodo())) {
            return List.of();
        }
        String especialidade = filtro.especialidadeId() == null ? "Cardiologia" : "Cardiologia";
        return List.of(
                new HorarioOferta(
                        "8001",
                        "901",
                        LocalDateTime.now().plusDays(2).withHour("tarde".equals(filtro.periodo()) ? 15 : 10)
                                .withMinute(0).withSecond(0).withNano(0),
                        filtro.medicoId() == null ? "Dr. Bruno Teste" : "Dra. Ana Teste",
                        especialidade,
                        "Consultório 2"
                ),
                new HorarioOferta(
                        "8002",
                        "901",
                        LocalDateTime.now().plusDays(3).withHour("tarde".equals(filtro.periodo()) ? 16 : 11)
                                .withMinute(0).withSecond(0).withNano(0),
                        filtro.medicoId() == null ? "Dr. Bruno Teste" : "Dra. Ana Teste",
                        especialidade,
                        "Consultório 2"
                )
        );
    }

    @Override
    public List<ItemCatalogo> exames() {
        return List.of(
                ItemCatalogo.real("1056", "Ecocardiograma", false),
                ItemCatalogo.real("437", "Gamagrafia Óssea", false)
        );
    }

    @Override
    public List<HorarioOferta> disponibilidadeExame(
            String exameId,
            LocalDateTime inicio,
            LocalDateTime fim,
            String periodo
    ) {
        if (periodosSemHorario.contains(periodo)) {
            return List.of();
        }
        int hora = "tarde".equals(periodo) ? 15 : 9;
        return List.of(
                new HorarioOferta(
                        "9101",
                        "213",
                        LocalDateTime.now().plusDays(2).withHour(hora).withMinute(0).withSecond(0).withNano(0),
                        null,
                        null,
                        "Agenda Ecocardiogramas"
                ),
                new HorarioOferta(
                        "9102",
                        "213",
                        LocalDateTime.now().plusDays(2).withHour(hora).withMinute(30).withSecond(0).withNano(0),
                        null,
                        null,
                        "Agenda Ecocardiogramas"
                )
        );
    }

    @Override
    public ProcedimentoExame procedimentoExame(String exameId) {
        return new ProcedimentoExame(exameId, "50001750", "4", null);
    }

    @Override
    public String orientacaoExame(String exameId) {
        return "1056".equals(exameId) ? "1. CHEGADA\n• Chegar com 30 minutos de antecedência." : "";
    }

    @Override
    public ResultadoEscrita agendar(PedidoAgendar pedido) {
        escritas.add(pedido.isExame() ? "agendar_exame" : "agendar");
        return new ResultadoEscrita(CodigoNegocio.SUCESSO);
    }

    @Override
    public ResultadoEscrita confirmar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        escritas.add("confirmar");
        return new ResultadoEscrita(CodigoNegocio.SUCESSO);
    }

    @Override
    public ResultadoEscrita cancelar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        escritas.add("cancelar");
        return new ResultadoEscrita(CodigoNegocio.SUCESSO);
    }

    @Override
    public ResultadoEscrita remarcar(
            String chaveIdempotencia,
            String pacienteId,
            String nrAtual,
            PedidoAgendar novoHorario
    ) {
        escritas.add("remarcar");
        return new ResultadoEscrita(CodigoNegocio.SUCESSO);
    }
}
