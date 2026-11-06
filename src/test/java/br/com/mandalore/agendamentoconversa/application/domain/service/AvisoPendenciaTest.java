package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaMandalorePort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;

class AvisoPendenciaTest {

    private final ChatwootPort chatwoot = mock(ChatwootPort.class);
    private final AgendaMandalorePort agenda = mock(AgendaMandalorePort.class);
    private final AvisoPendencia aviso = new AvisoPendencia(chatwoot, agenda, "https://bs-api.exemplo.com/", Runnable::run);

    @Test
    void pendenciaNaConversaGanhaNotaEtiquetaEEquipeSemAbrirParaAtendente() {
        aviso.criadas("2", List.of(pendencia(7, "20"), pendencia(8, "20")));

        ArgumentCaptor<String> nota = ArgumentCaptor.forClass(String.class);
        verify(chatwoot, times(2)).notaPrivada(org.mockito.ArgumentMatchers.eq("2"), org.mockito.ArgumentMatchers.eq("20"), nota.capture());
        assertThat(nota.getValue()).startsWith("Pendência da agenda: Lançar")
                .endsWith("Marque como feito em https://bs-api.exemplo.com/agenda?pendencia=8");
        verify(chatwoot, times(2)).etiqueta("2", "20", AvisoPendencia.ETIQUETA, true);
        verify(chatwoot).atribuirEquipe("2", "20", Setor.AGENDAMENTO);
        verify(chatwoot, never()).abrirAtendente(anyString(), anyString());
    }

    @Test
    void pendenciaSemConversaFicaSoNaTela() {
        aviso.criadas("2", List.of(pendencia(7, null)));

        verifyNoInteractions(chatwoot);
    }

    @Test
    void concluirAUltimaPendenciaDeixaNotaTiraAEtiquetaEResolveAConversa() {
        when(agenda.pendencia("2", 7L)).thenReturn(Optional.of(concluida(7, "20")));
        when(agenda.pendenciasAbertasDaConversa("2", "20")).thenReturn(0L);

        aviso.concluida("2", 7L);

        ArgumentCaptor<String> nota = ArgumentCaptor.forClass(String.class);
        verify(chatwoot).notaPrivada(eq("2"), eq("20"), nota.capture());
        assertThat(nota.getValue()).startsWith("Feito no sistema da clínica: Lançar no sistema da clínica")
                .contains("Paciente: Maria", "Código no sistema da clínica: X1", "Por: ana em sex. 06/11/2026 às 09:45");
        verify(chatwoot).etiqueta("2", "20", AvisoPendencia.ETIQUETA, false);
        verify(chatwoot).resolverSePendente("2", "20");
    }

    @Test
    void comOutraPendenciaAbertaAConversaContinuaComEtiqueta() {
        when(agenda.pendencia("2", 7L)).thenReturn(Optional.of(concluida(7, "20")));
        when(agenda.pendenciasAbertasDaConversa("2", "20")).thenReturn(1L);

        aviso.concluida("2", 7L);

        verify(chatwoot).etiqueta("2", "20", AvisoPendencia.ETIQUETA, true);
        verify(chatwoot, never()).resolverSePendente(anyString(), anyString());
    }

    @Test
    void etiquetaSaiQuandoNaoHaMaisPendenciaAberta() {
        when(agenda.pendenciasAbertasDaConversa("2", "20")).thenReturn(0L);

        aviso.atualizada("2", "20");

        verify(chatwoot).etiqueta("2", "20", AvisoPendencia.ETIQUETA, false);
    }

    private static Agenda.Pendencia concluida(long id, String conversa) {
        return new Agenda.Pendencia(id, 1, Agenda.TipoPendencia.LANCAR, Agenda.SituacaoPendencia.CONCLUIDA, "Lançar",
                LocalDateTime.of(2026, 11, 9, 9, 0), Instant.now(),
                ZonedDateTime.of(2026, 11, 6, 9, 45, 0, 0, ZoneId.of("America/Sao_Paulo")).toInstant(), "ana", "X1",
                "", conversa, LocalDateTime.of(2026, 11, 9, 8, 0), "Maria");
    }

    private static Agenda.Pendencia pendencia(long id, String conversa) {
        return new Agenda.Pendencia(id, 1, Agenda.TipoPendencia.LANCAR, Agenda.SituacaoPendencia.ABERTA,
                "Lançar no sistema da clínica\nPaciente: Maria", LocalDateTime.of(2026, 11, 9, 9, 0), Instant.now(), null, null, null,
                "", conversa, LocalDateTime.of(2026, 11, 9, 8, 0), "Maria");
    }
}
