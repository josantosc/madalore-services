package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AgendaUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaMandalorePort;

class AgendaServiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 11, 10, 7, 0);

    private final AgendaMandalorePort port = mock(AgendaMandalorePort.class);
    private final AvisoPendencia aviso = mock(AvisoPendencia.class);
    private final AgendaService service = new AgendaService(port, aviso, Clock.fixed(AGORA.atZone(SP).toInstant(), SP), 60);

    @Test
    void marcarPelaRecepcaoValidaOsDadosEAvisaAPendencia() {
        Agenda.Pendencia pendencia = pendencia(Agenda.TipoPendencia.LANCAR);
        when(port.marcar(eq("1"), any())).thenReturn(new Agenda.Operacao(CodigoNegocio.SUCESSO, 10L, List.of(pendencia)));

        service.marcar("1", consulta("529.982.247-25", "Maria", ""), "ana");

        ArgumentCaptor<Agenda.Marcacao> marcacao = ArgumentCaptor.forClass(Agenda.Marcacao.class);
        verify(port).marcar(eq("1"), marcacao.capture());
        assertThat(marcacao.getValue().paciente().cpf()).isEqualTo("52998224725");
        assertThat(marcacao.getValue().fim()).isEqualTo(LocalDateTime.of(2026, 11, 11, 9, 30));
        assertThat(marcacao.getValue().origem()).isEqualTo(Agenda.Origem.RECEPCAO);
        assertThat(marcacao.getValue().usuario()).isEqualTo("ana");
        verify(aviso).criadas("1", List.of(pendencia));
    }

    @Test
    void horarioSoAceitaEspecialidadeVinculadaAoProfissional() {
        when(port.especialidadesDosProfissionais("1")).thenReturn(java.util.Map.of(7L, List.of(3L)));
        Agenda.Grade cardio = grade(3);
        when(port.salvarGrade("1", cardio)).thenReturn(cardio);

        assertThat(service.salvarGrade("1", cardio)).isEqualTo(cardio);
        assertThatThrownBy(() -> service.salvarGrade("1", grade(4))).hasMessageContaining("não está vinculada");
        verify(port, never()).salvarGrade("1", grade(4));
    }

    @Test
    void naoDesvinculaEspecialidadeQueTemHorario() {
        when(port.grades("1")).thenReturn(List.of(grade(3), grade(3)));
        when(port.itens("1", Agenda.Cadastro.ESPECIALIDADE)).thenReturn(List.of(new Agenda.Item(3L, "Cardiologia", true)));

        assertThatThrownBy(() -> service.vincularEspecialidades("1", 7, List.of(4L)))
                .hasMessageContaining("Cardiologia (2 horários)");
        verify(port, never()).vincularEspecialidades(any(), anyLong(), any());

        service.vincularEspecialidades("1", 7, List.of(3L, 4L, 4L));
        verify(port).vincularEspecialidades("1", 7, List.of(3L, 4L));
    }

    private static Agenda.Grade grade(long especialidade) {
        return new Agenda.Grade(null, 7, especialidade, 2, null, null, null, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(12, 0),
                30, true, "", true);
    }

    @Test
    void marcarSemCpfOuNomeNaoChegaAoBanco() {
        assertThatThrownBy(() -> service.marcar("1", consulta("123", "Maria", ""), "ana")).hasMessageContaining("CPF");
        assertThatThrownBy(() -> service.marcar("1", consulta("52998224725", " ", ""), "ana")).hasMessageContaining("nome");
        verify(port, never()).marcar(any(), any());
    }

    @Test
    void concluirLancamentoExigeONumeroDoSistemaDaClinica() {
        when(port.pendencia("1", 5L)).thenReturn(Optional.of(pendencia(Agenda.TipoPendencia.LANCAR)));

        assertThatThrownBy(() -> service.concluirPendencia("1", 5L, " ", "", "ana")).hasMessageContaining("número");
        verify(port, never()).concluirPendencia(any(), anyLong(), any(), any(), any());
    }

    @Test
    void concluirCancelamentoNaoExigeNumeroEAvisaAConversa() {
        when(port.pendencia("1", 5L)).thenReturn(Optional.of(pendencia(Agenda.TipoPendencia.CANCELAR)));
        when(port.concluirPendencia("1", 5L, "ana", "", "")).thenReturn(true);

        assertThat(service.concluirPendencia("1", 5L, "", "", "ana")).isTrue();
        verify(aviso).concluida("1", 5L);
    }

    @Test
    void cancelarQueDescartaOLancamentoAtualizaAEtiqueta() {
        when(port.agendamento("1", 10L)).thenReturn(Optional.of(GradeHorariosTest.agendamento(AGORA.plusDays(1), Agenda.Status.MARCADO)));
        when(port.cancelar(eq("1"), eq(10L), any())).thenReturn(new Agenda.Operacao(CodigoNegocio.SUCESSO, 10L, List.of()));

        service.cancelar("1", 10L, false, "ana");

        verify(aviso).criadas("1", List.of());
        verify(aviso).atualizada(eq("1"), any());
    }

    @Test
    void cadastroComNomeRepetidoNaoChegaAoBancoMasRenomearOProprioPode() {
        Agenda.Item clinica = new Agenda.Item(3L, "Clínica Geral", true);
        when(port.itens("1", Agenda.Cadastro.ESPECIALIDADE)).thenReturn(List.of(clinica, new Agenda.Item(4L, "Cardiologia", false)));

        assertThatThrownBy(() -> service.salvarItem("1", Agenda.Cadastro.ESPECIALIDADE, new Agenda.Item(null, " clinica   geral ", true)))
                .hasMessage("Já existe uma especialidade com esse nome");
        assertThatThrownBy(() -> service.salvarItem("1", Agenda.Cadastro.ESPECIALIDADE, new Agenda.Item(3L, "CARDIOLOGIA", true)))
                .as("inativo também conta").hasMessageContaining("Já existe");
        verify(port, never()).salvarItem(any(), any(), any());

        Agenda.Item renomeado = new Agenda.Item(3L, "clínica geral", true);
        service.salvarItem("1", Agenda.Cadastro.ESPECIALIDADE, renomeado);
        verify(port).salvarItem("1", Agenda.Cadastro.ESPECIALIDADE, renomeado);
    }

    @Test
    void divergenciaPrecisaDeDescricao() {
        assertThatThrownBy(() -> service.conferir("1", 10L, Agenda.SituacaoConferencia.DIVERGENTE, "", "ana"))
                .hasMessageContaining("Descreva");
        service.conferir("1", 10L, Agenda.SituacaoConferencia.CONFERIDO, "", "ana");
        verify(port).conferir("1", 10L, Agenda.SituacaoConferencia.CONFERIDO, "", "ana");
    }

    private static AgendaUseCase.NovaConsulta consulta(String cpf, String nome, String codigo) {
        return new AgendaUseCase.NovaConsulta(7, 3, null, LocalDateTime.of(2026, 11, 11, 9, 0), 30, "", cpf,
                LocalDate.of(1988, 10, 19), nome, "", codigo);
    }

    private static Agenda.Pendencia pendencia(Agenda.TipoPendencia tipo) {
        return new Agenda.Pendencia(5, 10, tipo, Agenda.SituacaoPendencia.ABERTA, "Lançar", AGORA.plusMinutes(30), null, null, null,
                null, "", "55", AGORA.plusDays(1), "Maria");
    }
}
