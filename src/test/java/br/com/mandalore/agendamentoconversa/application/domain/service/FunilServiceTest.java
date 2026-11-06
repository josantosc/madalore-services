package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil.Etapa;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.FunilPort;

class FunilServiceTest {

    // Sexta-feira, 06/11/2026, 10h em São Paulo.
    private static final Instant AGORA = Instant.parse("2026-11-06T13:00:00Z");
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 6);

    private FunilPort port;
    private ChatwootPort chatwoot;
    private FunilService service;

    @BeforeEach
    void setUp() {
        port = mock(FunilPort.class);
        chatwoot = mock(ChatwootPort.class);
        service = new FunilService(port, chatwoot, Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo")), 15,
                "https://bs-api/");
    }

    @Test
    void orcamentoPorFotoAbreComRetornoHojeEPoeAEtiqueta() {
        service.abrirOrcamento("1", "7", new BigDecimal("37.00"), 2);

        verify(port).abrir("1", "7", Funil.Origem.ORCAMENTO_FOTO, new BigDecimal("37.00"), 2, HOJE, FunilService.SISTEMA);
        verify(chatwoot).etiqueta("1", "7", "orcamento", true);
    }

    @Test
    void falhaNaEtiquetaNaoDesfazAOportunidade() {
        doThrow(new IllegalStateException("chatwoot fora")).when(chatwoot).etiqueta(any(), any(), any(), anyBoolean());

        service.abrirOrcamento("1", "7", null, null);

        verify(port).abrir("1", "7", Funil.Origem.ORCAMENTO_FOTO, null, null, HOJE, FunilService.SISTEMA);
    }

    @Test
    void etiquetaOrcamentoAbreManualSoSemOportunidadeAberta() {
        when(port.aberta("1", "7")).thenReturn(Optional.empty());
        service.registrar(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "7", null, List.of("orcamento")));
        verify(port).abrir("1", "7", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, FunilService.SISTEMA);

        when(port.aberta("1", "8")).thenReturn(Optional.of(oportunidade(Etapa.NOVO)));
        service.registrar(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "8", null, List.of("orcamento")));
        service.registrar(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "9", null, List.of("vip")));
        verify(port, never()).abrir(eq("1"), eq("8"), any(), any(), any(), any(), any());
        verify(port, never()).abrir(eq("1"), eq("9"), any(), any(), any(), any(), any());
    }

    @Test
    void primeiraRespostaDoAtendenteEnviaOOrcamentoComRetornoEmDoisDiasUteis() {
        when(port.aberta("1", "7")).thenReturn(Optional.of(oportunidade(Etapa.NOVO)));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null));

        // Sexta + 2 dias úteis = terça.
        verify(port).mudar(10L, Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null, LocalDate.of(2026, 11, 10), "Ana", "Ana");
    }

    @Test
    void novaMensagemDoAtendenteEmpurraORetorno() {
        when(port.aberta("1", "7")).thenReturn(Optional.of(oportunidade(Etapa.EM_NEGOCIACAO)));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null));

        verify(port).proximaAcao(10L, LocalDate.of(2026, 11, 11), "Ana");
        verify(port, never()).mudar(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void respostaDoPacienteDepoisDoOrcamentoVaiParaNegociacao() {
        when(port.aberta("1", "7")).thenReturn(Optional.of(oportunidade(Etapa.ORCAMENTO_ENVIADO)));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, "1", "7", null, null));

        verify(port).respondeu(10L);
        verify(port).mudar(10L, Etapa.ORCAMENTO_ENVIADO, Etapa.EM_NEGOCIACAO, null, LocalDate.of(2026, 11, 9), null,
                FunilService.SISTEMA);
    }

    @Test
    void etiquetaJaGuardaPacienteEAtendenteAtribuido() {
        when(port.aberta("1", "7")).thenReturn(Optional.empty());
        when(port.abrir(any(), any(), any(), any(), any(), any(), any())).thenReturn(11L);

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "7", null, List.of("orcamento"), "Maria", "Bruna"));

        verify(port).contato(11L, "Maria", "Bruna");
    }

    @Test
    void responsavelEhOAtribuidoOuQuemRespondeuEPacienteSoMudaSeDiferente() {
        Funil.Oportunidade comNomes = new Funil.Oportunidade(10L, "1", "7", Funil.Origem.ORCAMENTO_FOTO, Etapa.EM_NEGOCIACAO,
                null, null, null, "Bruna", HOJE, AGORA, AGORA, null, "Maria");
        when(port.aberta("1", "7")).thenReturn(Optional.of(comNomes));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, "1", "7", null, null, "Maria", "Bruna"));
        verify(port, never()).contato(anyLong(), any(), any());

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null, "Maria", null));
        verify(port).contato(10L, null, "Ana");

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "7", null, List.of(), "Maria Silva", "Carla"));
        verify(port).contato(10L, "Maria Silva", "Carla");
    }

    @Test
    void mensagemSemOportunidadeNaoMexeEmNada() {
        when(port.aberta(any(), any())).thenReturn(Optional.empty());

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, "1", "7", null, null));
        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null));

        verify(port, never()).respondeu(anyLong());
        verify(port, never()).proximaAcao(anyLong(), any(), any());
    }

    @Test
    void perdidoExigeMotivoEFechadaZeraORetorno() {
        when(port.buscar(10L)).thenReturn(Optional.of(oportunidade(Etapa.ORCAMENTO_ENVIADO)));
        when(port.mudar(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.mudarEtapa(10L, Etapa.PERDIDO, null, "ana"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("motivo");

        service.mudarEtapa(10L, Etapa.PERDIDO, Funil.MotivoPerda.PRECO, "ana");
        verify(port).mudar(10L, Etapa.ORCAMENTO_ENVIADO, Etapa.PERDIDO, Funil.MotivoPerda.PRECO, null, null, "ana");
    }

    @Test
    void reabrirOportunidadeFechadaVoltaComRetornoHoje() {
        when(port.buscar(10L)).thenReturn(Optional.of(oportunidade(Etapa.PERDIDO)));
        when(port.mudar(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        service.mudarEtapa(10L, Etapa.EM_NEGOCIACAO, Funil.MotivoPerda.PRECO, "ana");

        verify(port).mudar(10L, Etapa.PERDIDO, Etapa.EM_NEGOCIACAO, null, HOJE, null, "ana");
    }

    @Test
    void mudancaConcorrenteViraConflito() {
        when(port.buscar(10L)).thenReturn(Optional.of(oportunidade(Etapa.NOVO)));
        when(port.mudar(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.mudarEtapa(10L, Etapa.AGENDADO, null, "ana"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adiarRetornoNaoAceitaDataPassadaNemOportunidadeFechada() {
        when(port.buscar(10L)).thenReturn(Optional.of(oportunidade(Etapa.AGENDADO)));

        assertThatThrownBy(() -> service.adiarRetorno(10L, HOJE.minusDays(1), "ana"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.adiarRetorno(10L, HOJE.plusDays(3), "ana"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fechada");
        verify(port, never()).proximaAcao(anyLong(), any(), any());
    }

    @Test
    void fechamentoAutomaticoUsaOPrazoSemResposta() {
        service.fecharAutomaticas();

        verify(port).fecharAgendadasPelaTrilha();
        verify(port).fecharSemResposta(Instant.parse("2026-10-22T13:00:00Z"));
    }

    @Test
    void retornoDoDiaDeixaNotaPassaParaFaturamentoEAbreAConversa() {
        Funil.Oportunidade o = new Funil.Oportunidade(10L, "1", "7", Funil.Origem.ORCAMENTO_FOTO, Etapa.ORCAMENTO_ENVIADO,
                null, new BigDecimal("37.00"), 2, "Ana", HOJE, Instant.parse("2026-11-03T15:00:00Z"), AGORA, null, "Maria");
        when(port.retornosPendentes(HOJE)).thenReturn(List.of(o));

        assertThat(service.avisarRetornos()).isEqualTo(1);

        verify(chatwoot).notaPrivada("1", "7", """
                Retorno do orçamento: orçamento enviado, sem resposta do paciente.
                Valor: R$ 37,00 (2 exames).
                Última mensagem do paciente há 3 dias.
                Até 24 horas depois da última mensagem do paciente dá para escrever aqui. Depois disso, ligue.
                Quando fechar, marque Agendado ou Perdido no funil: https://bs-api/funil""");
        verify(chatwoot).atribuirEquipe("1", "7", Setor.FATURAMENTO);
        verify(chatwoot).abrirAtendente("1", "7");
        verify(port).retornoAvisado(10L, HOJE);
    }

    @Test
    void falhaNaNotaNaoMarcaComoAvisado() {
        Funil.Oportunidade o = oportunidade(Etapa.NOVO);
        when(port.retornosPendentes(HOJE)).thenReturn(List.of(o));
        doThrow(new IllegalStateException("500")).when(chatwoot).notaPrivada(any(), any(), any());

        assertThat(service.avisarRetornos()).isZero();

        verify(port, never()).retornoAvisado(anyLong(), any());
    }

    private static Funil.Oportunidade oportunidade(Etapa etapa) {
        return new Funil.Oportunidade(10L, "1", "7", Funil.Origem.ORCAMENTO_FOTO, etapa, null, null, null, null,
                etapa.aberta() ? HOJE : null, AGORA, AGORA, etapa.aberta() ? null : AGORA, null);
    }
}
