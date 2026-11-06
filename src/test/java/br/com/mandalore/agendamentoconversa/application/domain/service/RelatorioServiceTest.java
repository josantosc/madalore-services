package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.NotificacaoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.RelatorioPort;

class RelatorioServiceTest {

    /** Quinta-feira, 05/11/2026, 8h em São Paulo. */
    private static final Clock CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 11, 5, 8, 0, 0, 0, RelatorioService.FUSO).toInstant(), RelatorioService.FUSO);

    private final Map<Instant, Relatorio.Numeros> porInicio = new HashMap<>();
    private final List<Instant[]> consultas = new ArrayList<>();
    private final List<String> enviados = new ArrayList<>();
    private final RelatorioPort port = (inicio, fim, limite) -> {
        consultas.add(new Instant[] {inicio, fim});
        return porInicio.getOrDefault(inicio, vazio());
    };
    private final RelatorioService service = new RelatorioService(
            port, Optional.<NotificacaoPort>of(enviados::add), CLOCK, 0.6, "BioSaúde", "https://bs-api.exemplo.com/");

    @Test
    void curadoriaResumeOntemComparandoCom7DiasAnteriores() {
        porInicio.put(dia(2026, 11, 4), numeros(200, 50, 10, 30, 12, 3, 40,
                Map.of("estacionamento", 5L, "contato", 2L), Map.of(), Map.of(), 0, Map.of("TASY", 2L)));
        porInicio.put(dia(2026, 10, 28), numeros(1000, 300, 80, 90, 0, 0, 0, Map.of(), Map.of(), Map.of(), 0, Map.of()));

        String texto = service.texto(Relatorio.Tipo.CURADORIA);

        assertThat(consultas.get(0)).containsExactly(dia(2026, 11, 4), dia(2026, 11, 5));
        assertThat(consultas.get(1)).containsExactly(dia(2026, 10, 28), dia(2026, 11, 4));
        assertThat(texto)
                .startsWith("Curadoria do bot - BioSaúde\nOntem, 04/11/2026")
                .contains("Mensagens: 200 em 50 conversas")
                .contains("Não entendidas: 10 (5,0%). Média dos 7 dias anteriores: 8,0%")
                .contains("Confiança baixa: 30 (15,0%)")
                .contains("Na fila de curadoria (7 dias): 12")
                .contains("Frases ensinadas: 3")
                .contains("Perguntas frequentes respondidas: 40")
                .contains("Perguntas frequentes sem texto: estacionamento 5, contato 2")
                .contains("Falhas registradas: TASY 2")
                .endsWith("Revisar a fila: https://bs-api.exemplo.com/curadoria");
    }

    @Test
    void curadoriaSemMensagens() {
        String texto = service.texto(Relatorio.Tipo.CURADORIA);

        assertThat(texto).contains("Nenhuma mensagem para o bot.").doesNotContain("Falhas registradas");
    }

    @Test
    void gestorCobreASemanaAnteriorDeSegundaADomingo() {
        porInicio.put(dia(2026, 10, 26), numeros(900, 120, 45, 0, 0, 0, 30, Map.of(),
                Map.of("consulta", 40L, "exame", 15L, "confirmar", 8L, "cancelar", 4L, "remarcar", 3L),
                Map.of("RECEPCAO", 10L, "LABORATORIO", 6L), 7, Map.of()));
        porInicio.put(dia(2026, 10, 19), numeros(800, 100, 56, 0, 0, 0, 20, Map.of(),
                Map.of("consulta", 35L), Map.of("RECEPCAO", 9L), 5, Map.of()));

        String texto = service.texto(Relatorio.Tipo.GESTOR);

        assertThat(consultas.get(0)).containsExactly(dia(2026, 10, 26), dia(2026, 11, 2));
        assertThat(texto)
                .startsWith("Relatório semanal do bot - BioSaúde\n26/10 a 01/11/2026")
                .contains("Conversas: 120 (100)")
                .contains("Consultas marcadas: 40 (35)")
                .contains("Exames marcados: 15 (0)")
                .contains("Confirmações: 8 (0)")
                .contains("Cancelamentos: 4 (0)")
                .contains("Remarcações: 3 (0)")
                .contains("Passadas para atendente: 16 (9)\n  Recepção 10, Laboratório 6")
                .contains("Pedidos de orçamento por foto: 7 (5)")
                .contains("Perguntas frequentes respondidas: 30 (20)")
                .endsWith("Mensagens não entendidas: 5,0% (7,0%)");
    }

    @Test
    void conversaoDeOntemComparandoCom7DiasAnteriores() {
        porInicio.put(dia(2026, 11, 4), conversao(50, 12, 8));
        porInicio.put(dia(2026, 10, 28), conversao(300, 60, 40));

        String texto = service.texto(Relatorio.Tipo.CONVERSAO);

        assertThat(consultas.get(0)).containsExactly(dia(2026, 11, 4), dia(2026, 11, 5));
        assertThat(consultas.get(1)).containsExactly(dia(2026, 10, 28), dia(2026, 11, 4));
        assertThat(texto)
                .startsWith("Conversão do bot - BioSaúde\nOntem, 04/11/2026")
                .contains("Conversas: 50")
                .contains("Marcaram consulta ou exame: 12 (24,0%). Média dos 7 dias anteriores: 20,0%")
                .contains("Passaram para atendente sem marcar: 8 (16,0%)")
                .contains("Sem agendamento nem atendente: 30 (60,0%)")
                .endsWith("Agendamentos feitos pela equipe depois do transbordo não entram.");
    }

    @Test
    void conversaoSemConversas() {
        assertThat(service.texto(Relatorio.Tipo.CONVERSAO)).endsWith("Nenhuma conversa com o bot.");
    }

    @Test
    void gestorTrazATaxaDeConversao() {
        porInicio.put(dia(2026, 10, 26), conversao(100, 25, 10));
        porInicio.put(dia(2026, 10, 19), conversao(80, 16, 10));

        assertThat(service.texto(Relatorio.Tipo.GESTOR))
                .contains("Conversão (conversas com consulta ou exame marcado pelo bot): 25,0% (20,0%)");
    }

    @Test
    void ultimosDiasContaHoje() {
        Relatorio.Periodo periodo = service.ultimosDias(7);

        assertThat(periodo.de()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(periodo.ate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(service.ultimosDias(0).de()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void enviaOTextoPelaNotificacao() {
        service.enviar(Relatorio.Tipo.CURADORIA);

        assertThat(enviados).singleElement().asString().startsWith("Curadoria do bot");
    }

    @Test
    void semTelegramOEnvioFicaComOOpenClaw() {
        RelatorioService semTelegram = new RelatorioService(port, Optional.empty(), CLOCK, 0.6, "", "");

        assertThatThrownBy(() -> semTelegram.enviar(Relatorio.Tipo.CURADORIA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenClaw");
    }

    @Test
    void periodoIncluiODiaFinal() {
        Relatorio.Periodo periodo = service.periodo(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3));

        assertThat(consultas).singleElement().satisfies(c ->
                assertThat(c).containsExactly(dia(2026, 11, 2), dia(2026, 11, 4)));
        assertThat(periodo.de()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(periodo.ate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void periodoRecusaIntervaloInvertidoOuLongo() {
        LocalDate inicio = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() -> service.periodo(inicio, inicio.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.periodo(inicio, inicio.plusDays(RelatorioService.MAX_DIAS)))
                .isInstanceOf(IllegalArgumentException.class);
        service.periodo(inicio, inicio.plusDays(RelatorioService.MAX_DIAS - 1L));
        assertThat(consultas).hasSize(1);
    }

    private static Instant dia(int ano, int mes, int dia) {
        return LocalDate.of(ano, mes, dia).atStartOfDay(RelatorioService.FUSO).toInstant();
    }

    private static Relatorio.Numeros vazio() {
        return numeros(0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), 0, Map.of());
    }

    @SuppressWarnings("java:S107")
    private static Relatorio.Numeros numeros(long mensagens, long conversas, long naoEntendidas, long baixa,
            long pendentes, long ensinadas, long faq, Map<String, Long> semResposta, Map<String, Long> escritas,
            Map<String, Long> transbordos, long orcamentos, Map<String, Long> falhas) {
        return new Relatorio.Numeros(mensagens, conversas, naoEntendidas, baixa, pendentes, ensinadas, faq,
                semResposta, escritas, transbordos, orcamentos, falhas, null);
    }

    private static Relatorio.Numeros conversao(long conversas, long convertidas, long atendente) {
        return new Relatorio.Numeros(0, conversas, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), 0, Map.of(),
                new Relatorio.Conversao(conversas, convertidas, atendente));
    }
}
