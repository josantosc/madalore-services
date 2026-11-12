package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import br.com.mandalore.agendamentoconversa.application.domain.exception.AcessoNegadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.AnamneseInBound;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.Contexto;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.DiagnosticoInBound;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.EvolucaoInBound;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.FinalizarInBound;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.Impressao;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.ReceitaInBound;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort.CabecalhoReceita;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort.ContagemClinica;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort.ProfissionalClinico;

class ConsultorioServiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 11, 10, 7, 0);
    private static final String CONTA = "1";
    private static final long PROF = 3L;
    private static final long ATEND = 50L;
    private static final long PACIENTE = 77L;
    private static final Contexto CTX = new Contexto(CONTA, 7L, "dr");

    private final AtendimentoPort port = mock(AtendimentoPort.class);
    private final HabilitacaoAtendimento habilitacao = mock(HabilitacaoAtendimento.class);
    private final ConsultorioService service =
            new ConsultorioService(port, habilitacao, Clock.fixed(AGORA.atZone(SP).toInstant(), SP), 10);

    // ---- posse ----

    @Test
    void usuarioSemVinculoComProfissionalEAcessoNegado() {
        when(port.profissionalDoUsuario(CONTA, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.iniciarConsulta(CTX, ATEND)).isInstanceOf(AcessoNegadoException.class);
    }

    @Test
    void atendimentoDeOutroProfissionalEAcessoNegado() {
        profissional();
        when(port.atendimento(CONTA, ATEND)).thenReturn(Optional.of(ficha(Atendimento.Status.AGUARDANDO, 99L)));

        assertThatThrownBy(() -> service.iniciarConsulta(CTX, ATEND)).isInstanceOf(AcessoNegadoException.class);

        verify(port, never()).iniciarConsulta(anyString(), anyLong(), anyLong(), anyString());
    }

    // ---- iniciar consulta ----

    @Test
    void iniciarConsultaAguardandoDelegaAoPort() {
        profissional();
        when(port.atendimento(CONTA, ATEND)).thenReturn(Optional.of(ficha(Atendimento.Status.AGUARDANDO, PROF)));
        Atendimento.Operacao ok = Atendimento.Operacao.sucesso(ATEND);
        when(port.iniciarConsulta(CONTA, ATEND, PROF, "dr")).thenReturn(ok);

        assertThat(service.iniciarConsulta(CTX, ATEND)).isSameAs(ok);
    }

    @Test
    void iniciarConsultaForaDeAguardandoDevolveStatusInvalido() {
        profissional();
        when(port.atendimento(CONTA, ATEND)).thenReturn(Optional.of(ficha(Atendimento.Status.EM_CONSULTA, PROF)));

        assertThat(service.iniciarConsulta(CTX, ATEND).codigo()).isEqualTo(CodigoNegocio.STATUS_INVALIDO_PARA_CONSULTA);

        verify(port, never()).iniciarConsulta(anyString(), anyLong(), anyLong(), anyString());
    }

    // ---- anamnese ----

    @Test
    void gravarAnamneseForaDeEmConsultaDevolveAtendimentoNaoEmConsulta() {
        profissional();
        when(port.atendimento(CONTA, ATEND)).thenReturn(Optional.of(ficha(Atendimento.Status.AGUARDANDO, PROF)));

        Atendimento.Operacao op = service.salvarRascunhoAnamnese(CTX, ATEND, anamneseIn("dor"));

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
    }

    @Test
    void liberarAnamneseSemQueixaLancaIllegalArgument() {
        emConsulta();
        when(port.prontuario(CONTA, ATEND)).thenReturn(Optional.of(prontuario(anamnese("  ", null))));

        assertThatThrownBy(() -> service.liberarAnamnese(CTX, ATEND)).isInstanceOf(IllegalArgumentException.class);

        verify(port, never()).liberarAnamnese(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void inativarAnamneseEmRascunhoDevolveRegistroNaoLiberado() {
        emConsulta();
        when(port.anamnese(CONTA, 8L)).thenReturn(Optional.of(anamnese("dor", null)));

        assertThat(service.inativarAnamnese(CTX, 8L, "erro").codigo()).isEqualTo(CodigoNegocio.REGISTRO_NAO_LIBERADO);
    }

    // ---- evolução ----

    @Test
    void evolucaoVaziaNaoLibera() {
        emConsulta();
        when(port.evolucao(CONTA, 4L)).thenReturn(Optional.of(evolucao(" ", null)));

        assertThatThrownBy(() -> service.liberarEvolucao(CTX, 4L)).isInstanceOf(IllegalArgumentException.class);

        verify(port, never()).liberarEvolucao(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void editarEvolucaoLiberadaDevolveRegistroJaLiberado() {
        emConsulta();
        when(port.evolucao(CONTA, 4L)).thenReturn(Optional.of(evolucao("texto", AGORA)));

        Atendimento.Operacao op = service.editarEvolucao(CTX, 4L, new EvolucaoInBound("novo", null, null, null));

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.REGISTRO_JA_LIBERADO);
        verify(port, never()).editarEvolucao(anyString(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void inativarEvolucaoSemJustificativaLancaIllegalArgument() {
        assertThatThrownBy(() -> service.inativarEvolucao(CTX, 4L, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- diagnóstico ----

    @ParameterizedTest
    @ValueSource(strings = {"J06", "J06.9", "j06", " j06.9 "})
    void cidValidoENormalizadoParaMaiusculas(String cid) {
        emConsulta();
        when(port.incluirDiagnostico(eq(CONTA), eq(ATEND), eq(PROF), any(), eq("dr")))
                .thenReturn(Atendimento.Operacao.sucesso(1L));

        service.incluirDiagnostico(CTX, ATEND, new DiagnosticoInBound(cid, "Resfriado", Atendimento.TipoDiagnostico.PRINCIPAL));

        ArgumentCaptor<Atendimento.Diagnostico> captor = ArgumentCaptor.forClass(Atendimento.Diagnostico.class);
        verify(port).incluirDiagnostico(eq(CONTA), eq(ATEND), eq(PROF), captor.capture(), eq("dr"));
        assertThat(captor.getValue().cid()).isEqualTo(cid.trim().toUpperCase());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "J6", "06J", "J06.999", "JJ06"})
    void cidInvalidoLancaIllegalArgument(String cid) {
        profissional();

        assertThatThrownBy(() -> service.incluirDiagnostico(CTX, ATEND,
                new DiagnosticoInBound(cid, "x", Atendimento.TipoDiagnostico.PRINCIPAL)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(port, never()).incluirDiagnostico(anyString(), anyLong(), anyLong(), any(), anyString());
    }

    // ---- receita ----

    @Test
    void criarReceitaSemItemOuPosologiaNaoLiberaESemConselhoEBloqueada() {
        emConsulta();
        when(port.receita(CONTA, 6L)).thenReturn(Optional.of(receita(null, item("Dipirona", " "))));

        assertThatThrownBy(() -> service.liberarReceita(CTX, 6L)).isInstanceOf(IllegalArgumentException.class);

        when(port.receita(CONTA, 6L)).thenReturn(Optional.of(receita(null)));
        assertThatThrownBy(() -> service.liberarReceita(CTX, 6L)).isInstanceOf(IllegalArgumentException.class);

        verify(port, never()).liberarReceita(anyString(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void liberarReceitaSemConselhoDevolveProfissionalSemConselho() {
        emConsulta(new ProfissionalClinico(PROF, "Dra. Ana", " ", null));
        when(port.receita(CONTA, 6L)).thenReturn(Optional.of(receita(null, item("Dipirona", "1 cp 6/6h"))));

        assertThat(service.liberarReceita(CTX, 6L).codigo()).isEqualTo(CodigoNegocio.PROFISSIONAL_SEM_CONSELHO);
    }

    @Test
    void liberarReceitaFelizCalculaValidadeComoHojeMaisDias() {
        emConsulta();
        when(port.receita(CONTA, 6L)).thenReturn(Optional.of(receita(null, item("Dipirona", "1 cp 6/6h"))));
        when(port.liberarReceita(CONTA, 6L, PROF, LocalDate.of(2026, 11, 20), "dr"))
                .thenReturn(Atendimento.Operacao.sucesso(6L));

        assertThat(service.liberarReceita(CTX, 6L).codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        verify(port).liberarReceita(CONTA, 6L, PROF, LocalDate.of(2026, 11, 20), "dr");
    }

    @Test
    void impressaoDeReceitaEmRascunhoERecusada() {
        emConsulta();
        when(port.receita(CONTA, 6L)).thenReturn(Optional.of(receita(null, item("Dipirona", "1 cp"))));

        assertThatThrownBy(() -> service.impressaoReceita(CTX, 6L, "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(port, never()).registrarAcesso(anyString(), any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void impressaoDeReceitaLiberadaEscapaHtmlERegistraAcesso() {
        emConsulta();
        when(port.receita(CONTA, 6L)).thenReturn(Optional.of(receita(AGORA, item("<script>alert(1)</script>", "1 cp"))));
        when(port.cabecalhoReceita(CONTA, 6L)).thenReturn(Optional.of(new CabecalhoReceita("Clínica & Cia", "Maria", "52998224725")));

        Impressao impressao = service.impressaoReceita(CTX, 6L, "1.2.3.4");

        assertThat(impressao.html()).doesNotContain("<script>").contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("Clínica &amp; Cia");
        verify(port).registrarAcesso(CONTA, ATEND, PACIENTE, "IMPRESSAO_RECEITA", "dr", "1.2.3.4");
    }

    // ---- finalizar ----

    @Test
    void finalizarComRascunhoPendenteDevolveRascunhoPendente() {
        emConsulta();
        when(port.contagemParaFinalizar(CONTA, ATEND)).thenReturn(new ContagemClinica(2, 1));

        assertThat(service.finalizar(CTX, ATEND, finalizarIn()).codigo()).isEqualTo(CodigoNegocio.RASCUNHO_PENDENTE);
    }

    @Test
    void finalizarSemRegistroClinicoLiberadoDevolveSemRegistroClinico() {
        emConsulta();
        when(port.contagemParaFinalizar(CONTA, ATEND)).thenReturn(new ContagemClinica(0, 0));

        assertThat(service.finalizar(CTX, ATEND, finalizarIn()).codigo()).isEqualTo(CodigoNegocio.SEM_REGISTRO_CLINICO);
    }

    @Test
    void finalizarFelizGravaAltaComDesfechoPadrao() {
        emConsulta();
        when(port.contagemParaFinalizar(CONTA, ATEND)).thenReturn(new ContagemClinica(1, 0));
        when(port.finalizar(eq(CONTA), eq(ATEND), eq(PROF), eq(Atendimento.MotivoAlta.ALTA_MEDICA), any(), eq("dr")))
                .thenReturn(Atendimento.Operacao.sucesso(ATEND));

        assertThat(service.finalizar(CTX, ATEND, finalizarIn()).codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        ArgumentCaptor<Atendimento.Alta> alta = ArgumentCaptor.forClass(Atendimento.Alta.class);
        verify(port).finalizar(eq(CONTA), eq(ATEND), eq(PROF), eq(Atendimento.MotivoAlta.ALTA_MEDICA), alta.capture(), eq("dr"));
        assertThat(alta.getValue().desfecho()).isEqualTo(Atendimento.Desfecho.ALTA);
    }

    @Test
    void escritaDepoisDeFinalizadoDevolveAtendimentoNaoEmConsulta() {
        profissional();
        when(port.atendimento(CONTA, ATEND)).thenReturn(Optional.of(ficha(Atendimento.Status.FINALIZADO, PROF)));

        assertThat(service.salvarRascunhoAnamnese(CTX, ATEND, anamneseIn("dor")).codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(service.criarEvolucao(CTX, ATEND, new EvolucaoInBound("texto", null, null, null)).codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(service.criarReceita(CTX, ATEND, new ReceitaInBound(null, "", List.of(item("X", "Y")))).codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(service.finalizar(CTX, ATEND, finalizarIn()).codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
    }

    // ---- prontuário ----

    @Test
    void prontuarioRegistraAcesso() {
        emConsulta();
        Atendimento.Prontuario prontuario = prontuario(null);
        when(port.prontuario(CONTA, ATEND)).thenReturn(Optional.of(prontuario));

        assertThat(service.prontuario(CTX, ATEND, "1.2.3.4")).isSameAs(prontuario);

        verify(port).registrarAcesso(CONTA, ATEND, PACIENTE, "PRONTUARIO", "dr", "1.2.3.4");
    }

    // ---- apoio ----

    private void profissional() {
        profissional(new ProfissionalClinico(PROF, "Dra. Ana", "12345", "SP"));
    }

    private void profissional(ProfissionalClinico prof) {
        when(port.profissionalDoUsuario(CONTA, 7L)).thenReturn(Optional.of(prof));
    }

    private void emConsulta() {
        emConsulta(new ProfissionalClinico(PROF, "Dra. Ana", "12345", "SP"));
    }

    private void emConsulta(ProfissionalClinico prof) {
        profissional(prof);
        when(port.atendimento(CONTA, ATEND)).thenReturn(Optional.of(ficha(Atendimento.Status.EM_CONSULTA, PROF)));
    }

    private static Atendimento.Ficha ficha(Atendimento.Status status, long profissionalId) {
        return new Atendimento.Ficha(ATEND, 20L, PACIENTE, "Maria", profissionalId, "Dra. Ana", 1L, "Clínica geral", null,
                status, AGORA, null, null, null, null, "ana", null, null, null, null, null);
    }

    private static AnamneseInBound anamneseIn(String queixa) {
        return new AnamneseInBound(queixa, null, null, null, null, null, null, null);
    }

    private static Atendimento.Anamnese anamnese(String queixa, LocalDateTime liberacao) {
        return new Atendimento.Anamnese(8L, ATEND, PACIENTE, PROF, queixa, "", "", "", "", "", "", "", AGORA, liberacao,
                true, null, null, null, "dr");
    }

    private static Atendimento.Evolucao evolucao(String texto, LocalDateTime liberacao) {
        return new Atendimento.Evolucao(4L, ATEND, PACIENTE, PROF, texto, null, null, null, AGORA, liberacao, true,
                null, null, null, "dr");
    }

    private static Atendimento.ReceitaItem item(String medicamento, String posologia) {
        return new Atendimento.ReceitaItem(1, medicamento, "", posologia, "", "", false, "");
    }

    private static Atendimento.Receita receita(LocalDateTime liberacao, Atendimento.ReceitaItem... itens) {
        return new Atendimento.Receita(6L, ATEND, PACIENTE, PROF, Atendimento.TipoReceita.COMUM, "", AGORA, liberacao,
                liberacao == null ? null : LocalDate.of(2026, 11, 20), true, null, null, null, "dr", List.of(itens));
    }

    private static Atendimento.Prontuario prontuario(Atendimento.Anamnese anamnese) {
        return new Atendimento.Prontuario(ficha(Atendimento.Status.EM_CONSULTA, PROF), anamnese, List.of(), List.of(),
                List.of(), null);
    }

    private static FinalizarInBound finalizarIn() {
        return new FinalizarInBound(Atendimento.MotivoAlta.ALTA_MEDICA, null, "Repouso", null);
    }
}
