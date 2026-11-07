package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.FeriadosNacionais;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil.Etapa;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.FunilPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.RegrasFunilPort;

class FunilServiceTest {

    // Sexta-feira, 06/11/2026, 10h em São Paulo.
    private static final Instant AGORA = Instant.parse("2026-11-06T13:00:00Z");
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 6);
    private static final LocalDate SEGUNDA = LocalDate.of(2026, 11, 9);
    private static final LocalDate TERCA = LocalDate.of(2026, 11, 10);
    private static final Funil.Regras PADRAO = new Funil.Regras(List.of(1, 2, 2), 2, false, Set.of());
    // Conta 2: um contato em 3 dias úteis, sábado útil e feriado na segunda 09/11.
    private static final Funil.Regras CONTA_2 = new Funil.Regras(List.of(3), 1, true, Set.of(SEGUNDA));

    private FunilPort port;
    private ChatwootPort chatwoot;
    private RegrasFunilPort regras;
    private FunilService service;

    @BeforeEach
    void setUp() {
        port = mock(FunilPort.class);
        chatwoot = mock(ChatwootPort.class);
        regras = mock(RegrasFunilPort.class);
        when(regras.padrao()).thenReturn(PADRAO);
        when(regras.salvas()).thenReturn(Map.of("2", new Funil.RegrasSalvas(CONTA_2, "gestora@clinica", AGORA)));
        when(regras.regras(any())).thenCallRealMethod();
        service = new FunilService(port, chatwoot, regras, Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo")), 15,
                "https://bs-api/");
    }

    @Test
    void orcamentoPorFotoAbreComRetornoHojeEPoeAEtiqueta() {
        service.abrirOrcamento("1", "7", Funil.Origem.ORCAMENTO_FOTO, new BigDecimal("37.00"), 2);

        verify(port).abrir("1", "7", Funil.Origem.ORCAMENTO_FOTO, new BigDecimal("37.00"), 2, HOJE, FunilService.SISTEMA);
        verify(chatwoot).etiqueta("1", "7", "orcamento", true);
    }

    @Test
    void valorInformadoPeloAtendenteFicaEmCentavosEValidaFaixa() {
        when(port.valor(anyLong(), any(), any(), any())).thenReturn(true);

        service.definirValor(10L, new BigDecimal("150.5"), "Ana");
        verify(port).valor(10L, new BigDecimal("150.50"), null, "Ana");

        service.definirValor(10L, null, "Ana");
        verify(port).valor(10L, null, null, "Ana");

        assertThatThrownBy(() -> service.definirValor(10L, new BigDecimal("-1"), "Ana")).hasMessageContaining("entre");
        assertThatThrownBy(() -> service.definirValor(10L, new BigDecimal("1000000"), "Ana")).hasMessageContaining("entre");
        when(port.valor(eq(99L), any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.definirValor(99L, BigDecimal.TEN, "Ana")).hasMessage("Oportunidade não encontrada");
    }

    @Test
    void comandoOrcamentoAbreManualGravaTotalEQuantidadeEPoeAEtiqueta() {
        when(port.abrir("1", "7", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, FunilService.SISTEMA)).thenReturn(10L);

        service.orcamentoCalculado("1", "7", new BigDecimal("37"), 3, "Ana");
        service.orcamentoCalculado("1", "7", new BigDecimal("37"), 3, " ");

        verify(port).valor(10L, new BigDecimal("37.00"), 3, "Ana");
        verify(port).valor(10L, new BigDecimal("37.00"), 3, "chatwoot");
        verify(chatwoot, times(2)).etiqueta("1", "7", "orcamento", true);
    }

    @Test
    void falhaNaEtiquetaNaoDesfazAOportunidade() {
        doThrow(new IllegalStateException("chatwoot fora")).when(chatwoot).etiqueta(any(), any(), any(), anyBoolean());

        service.abrirOrcamento("1", "7", Funil.Origem.ORCAMENTO_FOTO, null, null);

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
    void primeiraRespostaDoAtendenteEnviaOOrcamentoEAgendaOContato1() {
        when(port.aberta("1", "7")).thenReturn(Optional.of(oportunidade(Etapa.NOVO)));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null));

        // Sexta + 1 dia útil = segunda.
        verify(port).mudar(10L, Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null, SEGUNDA, 1, "Ana", "Ana");
    }

    @Test
    void cadenciaDaContaUsaSabadoEFeriadoDela() {
        when(port.aberta("2", "7")).thenReturn(Optional.of(oportunidade("2", Etapa.NOVO, 0, HOJE)));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "2", "7", "Ana", null));

        // Sábado conta, domingo não, segunda é feriado: sábado, terça, quarta.
        verify(port).mudar(10L, Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null, LocalDate.of(2026, 11, 11), 1, "Ana", "Ana");
    }

    @Test
    void mensagemAntesDoDiaDoContatoNaoMexeNaCadencia() {
        when(port.aberta("1", "7")).thenReturn(Optional.of(oportunidade("1", Etapa.ORCAMENTO_ENVIADO, 1, SEGUNDA)));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null));

        verify(port, never()).avancar(anyLong(), anyInt(), any(), any(), any());
        verify(port, never()).proximaAcao(anyLong(), any(), any());
    }

    @Test
    void mensagemNoDiaOuAtrasadaCumpreOContatoEAgendaOProximo() {
        when(port.aberta("1", "7")).thenReturn(Optional.of(oportunidade("1", Etapa.ORCAMENTO_ENVIADO, 1, HOJE.minusDays(1))));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null));

        // Contato 2 vence 2 dias úteis depois de hoje (sexta): terça.
        verify(port).avancar(10L, 2, TERCA, null, "Ana");
    }

    @Test
    void ultimoContatoEncerraACadencia() {
        when(port.aberta("1", "7")).thenReturn(Optional.of(oportunidade("1", Etapa.EM_NEGOCIACAO, 3, HOJE)));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, "1", "7", "Ana", null));

        verify(port).avancar(10L, 3, null, TERCA, "Ana");
    }

    @Test
    void respostaDoPacienteDepoisDoOrcamentoVaiParaNegociacaoNoContato1() {
        when(port.aberta("1", "7")).thenReturn(Optional.of(oportunidade("1", Etapa.ORCAMENTO_ENVIADO, 2, HOJE)));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, "1", "7", null, null));

        verify(port).respondeu(10L);
        verify(port).mudar(10L, Etapa.ORCAMENTO_ENVIADO, Etapa.EM_NEGOCIACAO, null, SEGUNDA, 1, null, FunilService.SISTEMA);
    }

    @Test
    void respostaDoPacienteEmNegociacaoRecomecaACadencia() {
        Funil.Oportunidade encerrada = new Funil.Oportunidade(10L, "1", "7", Funil.Origem.ORCAMENTO_FOTO, Etapa.EM_NEGOCIACAO,
                null, null, null, null, null, AGORA, AGORA, null, null, 3, TERCA);
        when(port.aberta("1", "7")).thenReturn(Optional.of(encerrada));
        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, "1", "7", null, null));
        verify(port).avancar(10L, 1, SEGUNDA, null, FunilService.SISTEMA);

        when(port.aberta("1", "8")).thenReturn(Optional.of(oportunidade("1", Etapa.EM_NEGOCIACAO, 1, HOJE)));
        service.registrar(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, "1", "8", null, null));
        verify(port).proximaAcao(10L, SEGUNDA, FunilService.SISTEMA);
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
                null, null, null, "Bruna", SEGUNDA, AGORA, AGORA, null, "Maria", 1, null);
        when(port.aberta("1", "7")).thenReturn(Optional.of(comNomes));

        service.registrar(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, "1", "7", null, List.of(), "Maria", "Bruna"));
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
        verify(port, never()).avancar(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    void perdidoExigeMotivoEFechadaZeraORetorno() {
        when(port.buscar(10L)).thenReturn(Optional.of(oportunidade(Etapa.ORCAMENTO_ENVIADO)));
        when(port.mudar(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.mudarEtapa(10L, Etapa.PERDIDO, null, "ana"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("motivo");

        service.mudarEtapa(10L, Etapa.PERDIDO, Funil.MotivoPerda.PRECO, "ana");
        verify(port).mudar(10L, Etapa.ORCAMENTO_ENVIADO, Etapa.PERDIDO, Funil.MotivoPerda.PRECO, null, null, null, "ana");
    }

    @Test
    void reabrirOportunidadeFechadaVoltaNoContato1ComRetornoHoje() {
        when(port.buscar(10L)).thenReturn(Optional.of(oportunidade(Etapa.PERDIDO)));
        when(port.mudar(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        service.mudarEtapa(10L, Etapa.EM_NEGOCIACAO, Funil.MotivoPerda.PRECO, "ana");

        verify(port).mudar(10L, Etapa.PERDIDO, Etapa.EM_NEGOCIACAO, null, HOJE, 1, null, "ana");
    }

    @Test
    void mudancaConcorrenteViraConflito() {
        when(port.buscar(10L)).thenReturn(Optional.of(oportunidade(Etapa.NOVO)));
        when(port.mudar(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(false);

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
    void fechamentoAutomaticoFechaEncerradasEUsaOLimiteSemResposta() {
        service.fecharAutomaticas();

        verify(port).fecharAgendadasPelaTrilha();
        verify(port).fecharEncerradas(HOJE);
        verify(port).fecharSemResposta(Instant.parse("2026-10-22T13:00:00Z"));
    }

    @Test
    void retornoDoDiaDeixaNotaComOContatoPassaParaFaturamentoEAbreAConversa() {
        Funil.Oportunidade o = new Funil.Oportunidade(10L, "1", "7", Funil.Origem.ORCAMENTO_FOTO, Etapa.ORCAMENTO_ENVIADO,
                null, new BigDecimal("37.00"), 2, "Ana", HOJE, Instant.parse("2026-11-03T15:00:00Z"), AGORA, null, "Maria",
                2, null);
        when(port.retornosPendentes(HOJE)).thenReturn(List.of(o));

        assertThat(service.avisarRetornos()).isEqualTo(1);

        verify(chatwoot).notaPrivada("1", "7", """
                Retorno do orçamento: orçamento enviado, sem resposta do paciente.
                Contato 2 de 3: ofereça dois horários para agendar.
                Valor: R$ 37,00 (2 exames).
                Última mensagem do paciente há 3 dias.
                Até 24 horas depois da última mensagem do paciente dá para escrever aqui. Depois disso, ligue.
                Quando fechar, marque Agendado ou Perdido no funil: https://bs-api/funil""");
        verify(chatwoot).atribuirEquipe("1", "7", Setor.FATURAMENTO);
        verify(chatwoot).abrirAtendente("1", "7");
        verify(port).retornoAvisado(10L, HOJE);
    }

    @Test
    void notaDoUltimoContatoAtrasadoAvisaOEncerramento() {
        Funil.Oportunidade o = oportunidade("1", Etapa.EM_NEGOCIACAO, 3, HOJE.minusDays(2));

        assertThat(service.notaRetorno(o, PADRAO, HOJE))
                .contains("Contato 3 de 3: avise que vai encerrar")
                .contains("Atrasado: vencia em 04/11.");
    }

    @Test
    void falhaNaNotaNaoMarcaComoAvisado() {
        Funil.Oportunidade o = oportunidade(Etapa.NOVO);
        when(port.retornosPendentes(HOJE)).thenReturn(List.of(o));
        doThrow(new IllegalStateException("500")).when(chatwoot).notaPrivada(any(), any(), any());

        assertThat(service.avisarRetornos()).isZero();

        verify(port, never()).retornoAvisado(anyLong(), any());
    }

    @Test
    void painelTrazAsRegrasDasClinicasQueOLoginEnxerga() {
        when(port.listar(any(), any())).thenReturn(List.of(oportunidade("5", Etapa.NOVO, 0, HOJE)));

        FunilUseCase.Painel todas = service.painel(Set.of(), Set.of("1"), true);
        assertThat(todas.regras()).extracting(FunilUseCase.RegrasDaConta::conta).containsExactly("1", "2", "5");
        assertThat(todas.diasSemResposta()).isEqualTo(15);
        assertThat(todas.podeEditarRegras()).isTrue();
        assertThat(todas.feriadosNacionais()).contains(LocalDate.of(2026, 11, 15), LocalDate.of(2027, 3, 26));

        FunilUseCase.Painel clinicas = service.painel(Set.of("2", "1"), Set.of("9"), false);
        assertThat(clinicas.regras()).containsExactly(
                new FunilUseCase.RegrasDaConta("1", PADRAO, false, null, null),
                new FunilUseCase.RegrasDaConta("2", CONTA_2, true, "gestora@clinica", AGORA));

        when(port.listar(any(), any())).thenReturn(List.of());
        when(regras.salvas()).thenReturn(Map.of());
        assertThat(service.painel(Set.of(), Set.of(), false).regras())
                .containsExactly(new FunilUseCase.RegrasDaConta(null, PADRAO, false, null, null));
    }

    @Test
    void salvarRegrasExigeAClinica() {
        service.salvarRegras(" 2 ", CONTA_2, "gestora@clinica");
        verify(regras).salvar("2", CONTA_2, "gestora@clinica");

        assertThatThrownBy(() -> service.salvarRegras(" ", CONTA_2, "x")).hasMessageContaining("clínica");
    }

    @Test
    void feriadosNacionaisIncluemSextaFeiraSantaEContamNosDiasUteis() {
        assertThat(FeriadosNacionais.feriado(LocalDate.of(2027, 3, 26))).isTrue();
        assertThat(FeriadosNacionais.feriado(LocalDate.of(2026, 4, 3))).isTrue();
        assertThat(FeriadosNacionais.feriado(LocalDate.of(2026, 11, 20))).isTrue();
        assertThat(FeriadosNacionais.feriado(LocalDate.of(2027, 2, 9))).isFalse();
        // Quinta 19/11 + 1 dia útil: sexta 20/11 é feriado nacional, vai para segunda 23/11.
        assertThat(PADRAO.diasUteis(LocalDate.of(2026, 11, 19), 1)).isEqualTo(LocalDate.of(2026, 11, 23));
    }

    @Test
    void regrasValidamACadenciaEContamDiasUteis() {
        assertThat(PADRAO.diasUteis(HOJE, 2)).isEqualTo(TERCA);
        assertThat(CONTA_2.diasUteis(HOJE, 1)).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(PADRAO.diasAte(1)).isEqualTo(1);
        assertThat(PADRAO.diasAte(9)).isEqualTo(2);
        assertThatThrownBy(() -> new Funil.Regras(List.of(), 2, false, Set.of())).hasMessageContaining("contatos");
        assertThatThrownBy(() -> new Funil.Regras(List.of(1, 0), 2, false, Set.of())).hasMessageContaining("Cada contato");
        assertThatThrownBy(() -> new Funil.Regras(List.of(1, 61), 2, false, Set.of())).hasMessageContaining("Cada contato");
        assertThatThrownBy(() -> new Funil.Regras(List.of(1), 0, false, Set.of())).hasMessageContaining("encerrar");
    }

    @Test
    void objetivoDeCadaContato() {
        assertThat(FunilService.objetivo(1, 3)).contains("dúvida");
        assertThat(FunilService.objetivo(2, 3)).contains("dois horários");
        assertThat(FunilService.objetivo(3, 3)).contains("encerrar");
        assertThat(FunilService.objetivo(1, 1)).contains("encerrar");
    }

    private static Funil.Oportunidade oportunidade(Etapa etapa) {
        return new Funil.Oportunidade(10L, "1", "7", Funil.Origem.ORCAMENTO_FOTO, etapa, null, null, null, null,
                etapa.aberta() ? HOJE : null, AGORA, AGORA, etapa.aberta() ? null : AGORA, null,
                etapa == Etapa.NOVO || !etapa.aberta() ? 0 : 1, null);
    }

    private static Funil.Oportunidade oportunidade(String conta, Etapa etapa, int toque, LocalDate proxima) {
        return new Funil.Oportunidade(10L, conta, "7", Funil.Origem.ORCAMENTO_FOTO, etapa, null, null, null, null,
                proxima, AGORA, AGORA, null, null, toque, null);
    }
}
