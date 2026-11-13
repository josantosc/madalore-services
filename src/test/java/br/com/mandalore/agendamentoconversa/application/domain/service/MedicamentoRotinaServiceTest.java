package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.MedicamentoRotinaPort;

class MedicamentoRotinaServiceTest {

    private static final Atendimento.MedicamentoRotina DIPIRONA =
            new Atendimento.MedicamentoRotina(null, "Dipirona", "500 mg comprimido", "1 cp de 6/6 horas", "Oral", false);

    private final MedicamentoRotinaPort port = mock(MedicamentoRotinaPort.class);
    private final HabilitacaoAtendimento habilitacao = mock(HabilitacaoAtendimento.class);
    private final MedicamentoRotinaService service = new MedicamentoRotinaService(port, habilitacao, List.of(DIPIRONA));

    @Test
    void listarSemeiaAListaPadraoAntesDeLer() {
        when(port.listar("7")).thenReturn(List.of(DIPIRONA));

        assertThat(service.listar("7")).containsExactly(DIPIRONA);

        InOrder ordem = inOrder(habilitacao, port);
        ordem.verify(habilitacao).exigir("7");
        ordem.verify(port).semearSeNova("7", List.of(DIPIRONA));
        ordem.verify(port).listar("7");
    }

    @Test
    void carregarPadraoSoNaContaHabilitada() {
        when(habilitacao.habilitada("7")).thenReturn(true);
        when(port.semearSeNova("7", List.of(DIPIRONA))).thenReturn(true);

        assertThat(service.carregarPadraoSeNova("7")).isTrue();
        assertThat(service.carregarPadraoSeNova("8")).isFalse();

        verify(port, never()).semearSeNova(org.mockito.ArgumentMatchers.eq("8"), any());
    }

    @Test
    void salvarLimpaEspacosEPreservaId() {
        when(port.salvar(any(), any(), any())).thenReturn(Atendimento.Operacao.sucesso(3L));

        service.salvar("7", new Atendimento.MedicamentoRotina(3L, "  Amoxicilina ", " 500 mg   cápsula", null, "Oral", true), "gestor");

        verify(port).salvar("7", new Atendimento.MedicamentoRotina(3L, "Amoxicilina", "500 mg cápsula", "", "Oral", true), "gestor");
    }

    @Test
    void salvarSemMedicamentoOuLongoDemaisERecusado() {
        assertThatThrownBy(() -> service.salvar("7", new Atendimento.MedicamentoRotina(null, "  ", "", "", "", false), "g"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Informe o medicamento");
        assertThatThrownBy(() -> service.salvar("7", null, "g")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.salvar("7",
                new Atendimento.MedicamentoRotina(null, "Dipirona", "", "x".repeat(501), "", false), "g"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Posologia");

        verify(port, never()).salvar(any(), any(), any());
    }

    @Test
    void restaurarIncluiOsQueFaltamDaListaPadrao() {
        when(port.incluirFaltantes("7", List.of(DIPIRONA), "gestor")).thenReturn(1);

        assertThat(service.restaurarPadrao("7", "gestor")).isEqualTo(1);
    }

    @Test
    void contaSemAtendimentoNaoChegaAoBanco() {
        doThrow(new AtendimentoDesabilitadoException()).when(habilitacao).exigir("9");

        assertThatThrownBy(() -> service.listar("9")).isInstanceOf(AtendimentoDesabilitadoException.class);
        assertThatThrownBy(() -> service.excluir("9", 1L)).isInstanceOf(AtendimentoDesabilitadoException.class);

        verifyNoInteractions(port);
    }

    @Test
    void lerCsvPulaCabecalhoELinhaVaziaERecusaColunaFaltando() throws Exception {
        String csv = "medicamento;apresentacao;posologia;via;uso_continuo\nLosartana;50 mg;1 cp ao dia;Oral;S\n\n";

        assertThat(MedicamentoRotinaService.lerCsv(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))))
                .containsExactly(new Atendimento.MedicamentoRotina(null, "Losartana", "50 mg", "1 cp ao dia", "Oral", true));
        assertThatThrownBy(() -> MedicamentoRotinaService.lerCsv(new ByteArrayInputStream(
                "cabecalho\nDipirona;500 mg;Oral;N\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listaPadraoDoSistemaTemItensCompletosESemRepeticao() {
        List<Atendimento.MedicamentoRotina> padrao = MedicamentoRotinaService.listaPadrao();
        Set<String> chaves = new HashSet<>();

        assertThat(padrao).hasSizeGreaterThan(100);
        assertThat(padrao).allSatisfy(m -> {
            assertThat(m.apresentacao()).isNotBlank();
            assertThat(m.posologia()).isNotBlank();
            assertThat(m.via()).isNotBlank();
            assertThat(chaves.add((m.medicamento() + "|" + m.apresentacao()).toLowerCase(Locale.ROOT)))
                    .as("repetido: %s %s", m.medicamento(), m.apresentacao()).isTrue();
        });
    }
}
