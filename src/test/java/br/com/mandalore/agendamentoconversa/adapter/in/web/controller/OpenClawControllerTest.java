package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.RelatorioUseCase;

class OpenClawControllerTest {

    private static final String TOKEN = "segredo-do-openclaw";

    private final RelatorioUseCase relatorios = mock(RelatorioUseCase.class);
    private final OpenClawController controller = new OpenClawController(relatorios, TOKEN);

    @Test
    void devolveOTextoDoRelatorio() {
        when(relatorios.texto(Relatorio.Tipo.GESTOR)).thenReturn("Relatório semanal do bot");

        var resposta = controller.relatorio("gestor", "Bearer " + TOKEN);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).containsEntry("tipo", "gestor").containsEntry("texto", "Relatório semanal do bot");
    }

    @Test
    void recusaSemTokenOuComTokenErrado() {
        assertThat(controller.relatorio("curadoria", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.relatorio("curadoria", "Bearer outro").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.relatorio("curadoria", "Basic " + TOKEN).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.numeros("2026-11-01", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(relatorios);
    }

    @Test
    void semOpenclawTokenConfiguradoFicaIndisponivel() {
        OpenClawController semToken = new OpenClawController(relatorios, " ");

        assertThat(semToken.relatorio("curadoria", "Bearer ").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(relatorios);
    }

    @Test
    void tipoInvalido() {
        assertThat(controller.relatorio("mensal", "Bearer " + TOKEN).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void numerosDeUmDiaQuandoNaoHaDataFinal() {
        LocalDate dia = LocalDate.of(2026, 11, 3);
        Relatorio.Numeros numeros = new Relatorio.Numeros(10, 4, 1, 2, 0, 0, 3,
                Map.of(), Map.of("consulta", 2L), Map.of("RECEPCAO", 1L), 0, Map.of(), new Relatorio.Conversao(4, 2, 1));
        when(relatorios.periodo(dia, dia)).thenReturn(new Relatorio.Periodo(dia, dia, numeros));

        var resposta = controller.numeros("2026-11-03", null, "bearer " + TOKEN);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody())
                .containsEntry("de", "2026-11-03")
                .containsEntry("ate", "2026-11-03")
                .containsEntry("numeros", numeros);
    }

    @Test
    void numerosComDataOuIntervaloInvalido() {
        when(relatorios.periodo(any(), any())).thenThrow(new IllegalArgumentException("Intervalo maior que 92 dias"));

        assertThat(controller.numeros("03/11/2026", null, "Bearer " + TOKEN).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var longo = controller.numeros("2026-01-01", "2026-12-31", "Bearer " + TOKEN);
        assertThat(longo.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longo.getBody()).containsEntry("erro", "Intervalo maior que 92 dias");
    }
}
