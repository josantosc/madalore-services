package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootVisitantePort;

class SimuladorChatwootServiceTest {

    private final ChatwootVisitantePort chatwoot = mock(ChatwootVisitantePort.class);
    private final SimuladorChatwootService service = new SimuladorChatwootService(chatwoot);

    @Test
    void mesmaSessaoReusaOVisitante() {
        when(chatwoot.criarVisitante()).thenReturn("tk");
        when(chatwoot.ler("tk")).thenReturn(new ConversaVisitante("27", "pending", List.of()));

        service.enviar("s1", "oi");
        service.enviar("s1", "1");

        verify(chatwoot, times(1)).criarVisitante();
        verify(chatwoot).enviar("tk", "oi");
        verify(chatwoot).enviar("tk", "1");
    }

    @Test
    void sessaoSemMensagemNaoCriaConversa() {
        ConversaVisitante conversa = service.ler("nova");

        assertThat(conversa.conversationId()).isNull();
        assertThat(conversa.mensagens()).isEmpty();
        verify(chatwoot, never()).criarVisitante();
    }
}
