package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.CheckinConfigPort;

class CheckinServiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 11, 13, 14, 0);
    private static final String SEGREDO = "segredo-do-webhook-123";
    private static final String CODIGO = CheckinService.codigo(SEGREDO, "1", AGORA.toLocalDate());
    private static final Atendimento.PacienteResumo MARIA = new Atendimento.PacienteResumo(7L, "MARIA SILVA",
            "12345678901", LocalDate.of(1980, 1, 2), null);

    private final AtendimentoUseCase atendimento = mock(AtendimentoUseCase.class);
    private final AtendimentoPort port = mock(AtendimentoPort.class);
    private final HabilitacaoAtendimento habilitacao = mock(HabilitacaoAtendimento.class);
    private final CheckinConfigPort config = mock(CheckinConfigPort.class);
    private CheckinService service;

    @BeforeEach
    void setUp() {
        when(habilitacao.habilitada("1")).thenReturn(true);
        when(config.segredo("1")).thenReturn(Optional.of(SEGREDO));
        when(config.whatsapp("1")).thenReturn(Optional.of("5547999990000"));
        when(port.pacientesPorCpf("1", "12345678901")).thenReturn(List.of(MARIA));
        service = new CheckinService(atendimento, port, habilitacao, config,
                Clock.fixed(AGORA.atZone(SP).toInstant(), SP));
    }

    @Test
    void codigoMudaPorDiaEPorContaSemCaracteresAmbiguos() {
        assertThat(CODIGO).hasSize(6).matches("[" + CheckinService.ALFABETO + "]{6}");
        assertThat(CheckinService.codigo(SEGREDO, "1", AGORA.toLocalDate())).isEqualTo(CODIGO);
        assertThat(CheckinService.codigo(SEGREDO, "1", AGORA.toLocalDate().plusDays(1))).isNotEqualTo(CODIGO);
        assertThat(CheckinService.codigo(SEGREDO, "2", AGORA.toLocalDate())).isNotEqualTo(CODIGO);
    }

    @Test
    void doDiaMontaLinkDoWhatsappComAMensagemPronta() {
        Atendimento.CheckinDoDia dia = service.doDia("1").orElseThrow();

        assertThat(dia.codigo()).isEqualTo(CODIGO);
        assertThat(dia.link()).isEqualTo("https://wa.me/5547999990000?text=Cheguei%20" + CODIGO);
    }

    @Test
    void semWhatsappConfiguradoNaoHaQr() {
        when(config.whatsapp("1")).thenReturn(Optional.empty());

        assertThat(service.doDia("1")).isEmpty();
    }

    @Test
    void checkinCompletoRegistraAChegadaComoWhatsapp() {
        when(port.visitasDoPaciente("1", 7L, false)).thenReturn(List.of(visita(50L, 14, 30, "CONFIRMADO")));
        when(atendimento.registrarChegada("1", 50L, Atendimento.CHEGADA_WHATSAPP))
                .thenReturn(Atendimento.Operacao.sucesso(900L));

        assertThat(service.responder("1", "10", "Cheguei " + CODIGO)).contains(CheckinService.PEDE_DADOS);
        Optional<String> resposta = service.responder("1", "10", "123.456.789-01 02/01/1980");

        assertThat(resposta).contains("Check-in feito, Maria! Sua chegada para a consulta das 14:30 com Dra. Ana "
                + "está registrada. Aguarde na recepção: você será chamado(a) pelo painel.");
        assertThat(service.responder("1", "10", "123.456.789-01 02/01/1980")).isEmpty();
    }

    @Test
    void codigoMinusculoTambemVale() {
        assertThat(service.responder("1", "10", "cheguei " + CODIGO.toLowerCase())).contains(CheckinService.PEDE_DADOS);
    }

    @Test
    void codigoDeOutroDiaERecusado() {
        String ontem = CheckinService.codigo(SEGREDO, "1", AGORA.toLocalDate().minusDays(1));

        assertThat(service.responder("1", "10", "Cheguei " + ontem)).contains(CheckinService.CODIGO_INVALIDO);
        assertThat(service.responder("1", "10", "123.456.789-01 02/01/1980")).isEmpty();
    }

    @Test
    void chegueiSemCodigoExplicaOQrSoEmMensagemCurta() {
        assertThat(service.responder("1", "10", "Já cheguei!")).contains(CheckinService.SEM_CODIGO);
        assertThat(service.responder("1", "10", "cheguei a marcar consulta com o cardiologista semana passada?"))
                .isEmpty();
    }

    @Test
    void antesDaJanelaDe60MinutosNaoRegistra() {
        when(port.visitasDoPaciente("1", 7L, false)).thenReturn(List.of(visita(50L, 15, 30, "MARCADO")));

        service.responder("1", "10", "Cheguei " + CODIGO);
        Optional<String> resposta = service.responder("1", "10", "12345678901 02/01/1980");

        assertThat(resposta).contains("Sua consulta é às 15:30. O check-in pelo WhatsApp abre 60 minutos antes, "
                + "a partir das 14:30.");
        verify(atendimento, never()).registrarChegada(anyString(), anyLong(), anyString());
    }

    @Test
    void atrasadoNoMesmoDiaAindaFazCheckinNaConsultaMaisRecente() {
        when(port.visitasDoPaciente("1", 7L, false)).thenReturn(List.of(
                visita(40L, 8, 0, "MARCADO"), visita(50L, 13, 30, "CONFIRMADO")));
        when(atendimento.registrarChegada("1", 50L, Atendimento.CHEGADA_WHATSAPP))
                .thenReturn(Atendimento.Operacao.sucesso(900L));

        service.responder("1", "10", "Cheguei " + CODIGO);
        assertThat(service.responder("1", "10", "123.456.789-01 02/01/1980")).get().asString()
                .startsWith("Check-in feito");
        verify(atendimento).registrarChegada("1", 50L, Atendimento.CHEGADA_WHATSAPP);
    }

    @Test
    void chegadaJaRegistradaAvisaSemRegistrarDeNovo() {
        when(port.visitasDoPaciente("1", 7L, false)).thenReturn(List.of(visita(50L, 14, 30, "AGUARDANDO")));

        service.responder("1", "10", "Cheguei " + CODIGO);

        assertThat(service.responder("1", "10", "123.456.789-01 02/01/1980")).contains(CheckinService.JA_FEITO);
        verify(atendimento, never()).registrarChegada(anyString(), anyLong(), anyString());
    }

    @Test
    void semConsultaHojeMandaParaARecepcao() {
        when(port.visitasDoPaciente("1", 7L, false)).thenReturn(List.of(
                new Atendimento.VisitaPaciente(50L, AGORA.plusDays(1), "Dra. Ana", "Clínica", null, "MARCADO", null,
                        null, null, null, List.of())));

        service.responder("1", "10", "Cheguei " + CODIGO);

        assertThat(service.responder("1", "10", "123.456.789-01 02/01/1980")).contains(CheckinService.SEM_CONSULTA);
    }

    @Test
    void falhaDoRegistroMandaParaARecepcao() {
        when(port.visitasDoPaciente("1", 7L, false)).thenReturn(List.of(visita(50L, 14, 30, "CONFIRMADO")));
        when(atendimento.registrarChegada("1", 50L, Atendimento.CHEGADA_WHATSAPP))
                .thenReturn(Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CHEGADA));

        service.responder("1", "10", "Cheguei " + CODIGO);

        assertThat(service.responder("1", "10", "123.456.789-01 02/01/1980")).contains(CheckinService.NAO_REGISTRADO);
    }

    @Test
    void tresTentativasErradasEncerramOCheckin() {
        service.responder("1", "10", "Cheguei " + CODIGO);

        assertThat(service.responder("1", "10", "123")).contains(CheckinService.DADOS_INVALIDOS);
        assertThat(service.responder("1", "10", "999.999.999-99 02/01/1980")).contains(CheckinService.NAO_ENCONTRADO);
        assertThat(service.responder("1", "10", "123.456.789-01 03/01/1980")).contains(CheckinService.LIMITE);
        assertThat(service.responder("1", "10", "123.456.789-01 02/01/1980")).isEmpty();
    }

    @Test
    void mensagemSemNumerosDuranteAEsperaVoltaParaOAssistente() {
        service.responder("1", "10", "Cheguei " + CODIGO);

        assertThat(service.responder("1", "10", "quero marcar outra consulta")).isEmpty();
        assertThat(service.responder("1", "10", "123.456.789-01 02/01/1980")).isEmpty();
    }

    @Test
    void contaSemAtendimentoNaoIntercepta() {
        when(habilitacao.habilitada("2")).thenReturn(false);

        assertThat(service.responder("2", "10", "Cheguei " + CODIGO)).isEmpty();
        assertThat(service.doDia("2")).isEmpty();
    }

    @Test
    void dadosAceitamAnoComDoisDigitos() {
        assertThat(CheckinService.dados("cpf 12345678901 nasc 2-1-80"))
                .contains(new CheckinService.Dados("12345678901", LocalDate.of(1980, 1, 2)));
        assertThat(CheckinService.dados("12345678901 31/02/1980")).isEmpty();
    }

    private static Atendimento.VisitaPaciente visita(long id, int hora, int minuto, String status) {
        return new Atendimento.VisitaPaciente(id, AGORA.toLocalDate().atTime(hora, minuto), "Dra. Ana", "Clínica",
                null, status, null, null, null, null, List.of());
    }
}
