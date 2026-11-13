package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AvisoFilaPort;

class AtendimentoServiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 11, 10, 7, 0);

    private final AtendimentoPort port = mock(AtendimentoPort.class);
    private final HabilitacaoAtendimento habilitacao = mock(HabilitacaoAtendimento.class);
    private final AtendimentoService service =
            new AtendimentoService(port, habilitacao, Clock.fixed(AGORA.atZone(SP).toInstant(), SP));

    @Test
    void filaSemDataUsaHojeEDelegaAoPort() {
        List<Atendimento.FilaItem> fila = List.of();
        when(port.fila("1", LocalDate.of(2026, 11, 10))).thenReturn(fila);

        assertThat(service.fila("1", null)).isSameAs(fila);

        verify(habilitacao).exigir("1");
        verify(port).fila("1", LocalDate.of(2026, 11, 10));
    }

    @Test
    void painelMostraSoONomeAbreviadoDasChamadasDeHoje() {
        LocalDateTime chamada = AGORA.plusMinutes(5);
        when(port.chamadas("1", LocalDate.of(2026, 11, 10), AtendimentoService.PAINEL_LIMITE)).thenReturn(List.of(
                new Atendimento.Chamada("MARIA DA SILVA OLIVEIRA", "Dra. Ana", chamada)));

        assertThat(service.chamadasDoPainel("1"))
                .containsExactly(new Atendimento.Chamada("Maria S. O.", "Dra. Ana", chamada));
        verify(habilitacao).exigir("1");
    }

    @Test
    void nomePublicoAbreviaSobrenomesEIgnoraParticulas() {
        assertThat(AtendimentoService.nomePublico("joão  dos santos e souza")).isEqualTo("João S. S.");
        assertThat(AtendimentoService.nomePublico("Ana")).isEqualTo("Ana");
        assertThat(AtendimentoService.nomePublico("  ")).isEmpty();
        assertThat(AtendimentoService.nomePublico(null)).isEmpty();
    }

    @Test
    void chegadaDelegaAoPort() {
        Atendimento.Operacao ok = Atendimento.Operacao.sucesso(5L);
        when(port.registrarChegada("1", 9L, "ana")).thenReturn(ok);

        assertThat(service.registrarChegada("1", 9L, "ana")).isSameAs(ok);

        verify(port).registrarChegada("1", 9L, "ana");
    }

    @Test
    void desfazerChegadaSemMotivoELancaIllegalArgument() {
        assertThatThrownBy(() -> service.desfazerChegada("1", 5L, "   ", "ana"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(port, org.mockito.Mockito.never()).desfazerChegada(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void contaNaoHabilitadaLancaDesabilitadoENuncaChamaOPort() {
        doThrow(new AtendimentoDesabilitadoException()).when(habilitacao).exigir("2");

        assertThatThrownBy(() -> service.fila("2", null)).isInstanceOf(AtendimentoDesabilitadoException.class);
        assertThatThrownBy(() -> service.registrarChegada("2", 1L, "ana"))
                .isInstanceOf(AtendimentoDesabilitadoException.class);

        verifyNoInteractions(port);
    }

    @Test
    void chegadaComSucessoAvisaAsTelasEFalhaNaoAvisa() {
        AvisoFilaPort aviso = mock(AvisoFilaPort.class);
        AtendimentoService comAviso = new AtendimentoService(port, habilitacao,
                Clock.fixed(AGORA.atZone(SP).toInstant(), SP), aviso);
        when(port.registrarChegada("1", 9L, "ana")).thenReturn(Atendimento.Operacao.sucesso(5L));
        when(port.registrarChegada("1", 10L, "ana"))
                .thenReturn(Atendimento.Operacao.falha(CodigoNegocio.CHEGADA_FORA_DO_DIA));

        comAviso.registrarChegada("1", 9L, "ana");
        comAviso.registrarChegada("1", 10L, "ana");

        verify(aviso).publicar("1", new AvisoFilaPort.Aviso("CHEGADA", 9L, 5L));
        verifyNoMoreInteractions(aviso);
    }

    @Test
    void falhaNoAvisoNaoDesfazAOperacao() {
        AvisoFilaPort aviso = (conta, a) -> {
            throw new IllegalStateException("sem conexão");
        };
        AtendimentoService comAviso = new AtendimentoService(port, habilitacao,
                Clock.fixed(AGORA.atZone(SP).toInstant(), SP), aviso);
        when(port.chamar("1", 5L, null, "ana")).thenReturn(Atendimento.Operacao.sucesso(5L));

        assertThat(comAviso.chamar("1", 5L, "ana").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
    }

    @Test
    void encaixeLimpaCpfENomeEUsaDuracaoPadrao() {
        Atendimento.Encaixe in = new Atendimento.Encaixe(3L, 4L, null, "123.456.789-01",
                LocalDate.of(1980, 1, 2), "  Maria  ", null);
        when(port.encaixe(eq("1"), any(), eq(AtendimentoService.ENCAIXE_DURACAO_MIN), eq("ana")))
                .thenReturn(Atendimento.Operacao.sucesso(8L));

        assertThat(service.encaixe("1", in, "ana").id()).isEqualTo(8L);

        verify(port).encaixe("1", new Atendimento.Encaixe(3L, 4L, null, "12345678901", LocalDate.of(1980, 1, 2),
                "Maria", ""), AtendimentoService.ENCAIXE_DURACAO_MIN, "ana");
    }

    @Test
    void encaixeInvalidoNaoChamaOPort() {
        LocalDate nascimento = LocalDate.of(1980, 1, 2);
        assertThatThrownBy(() -> service.encaixe("1", new Atendimento.Encaixe(3L, 4L, null, "123", nascimento,
                "Maria", ""), "ana")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.encaixe("1", new Atendimento.Encaixe(3L, 4L, null, "12345678901",
                LocalDate.of(2027, 1, 1), "Maria", ""), "ana")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.encaixe("1", new Atendimento.Encaixe(0L, 4L, null, "12345678901",
                nascimento, "Maria", ""), "ana")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.encaixe("1", new Atendimento.Encaixe(3L, 4L, null, "12345678901",
                nascimento, " ", ""), "ana")).isInstanceOf(IllegalArgumentException.class);

        verify(port, never()).encaixe(anyString(), any(), anyInt(), anyString());
    }

    @Test
    void buscaComTermoCurtoNaoConsulta() {
        assertThat(service.buscarPacientes("1", " a ")).isEmpty();

        verify(port, never()).buscarPacientes(anyString(), anyString(), anyInt());
    }
}
