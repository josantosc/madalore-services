package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;

@ExtendWith(MockitoExtension.class)
class MensagemChatwootServiceTest {

    @Mock
    private DialogflowPort dialogflow;

    @Mock
    private ChatwootPort chatwoot;

    @InjectMocks
    private MensagemChatwootService service;

    @Test
    void ignoraMensagemDeSaida() {
        service.receber(mensagem("outgoing", "oi", "1", "pending", Canal.SITE));

        verify(dialogflow, never()).detectar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void conversaAbertaNaoChamaDialogflow() {
        service.receber(mensagem("incoming", "oi", "2", "open", Canal.SITE));

        verify(dialogflow, never()).detectar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(chatwoot, never()).publicar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void transbordoPublicaTextoEAbreConversa() {
        when(dialogflow.detectar("cw-8-9", "atendente"))
                .thenReturn(new RespostaDialogflow("transbordo.atendente", "Vou chamar alguém da equipe."));

        service.receber(mensagem("incoming", "atendente", "3", "pending", Canal.WHATSAPP));

        verify(chatwoot).publicar("8", "9", "Vou chamar alguém da equipe.");
        verify(chatwoot).abrirAtendente("8", "9");
    }

    @Test
    void textoVazioPedeDigitacao() {
        service.receber(mensagem("incoming", "  ", "4", "pending", Canal.SITE));

        verify(chatwoot).publicar("8", "9", MensagemChatwootService.SEM_TEXTO);
        verify(dialogflow, never()).detectar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void instagramDivideRespostaEmPartesDeAte1000Bytes() {
        String linha = "Orientação de preparo do exame: jejum de oito horas.";
        String longo = String.join("\n", java.util.Collections.nCopies(40, linha));
        when(dialogflow.detectar("cw-8-9", "preparo")).thenReturn(new RespostaDialogflow("texto.preparo", longo));

        service.receber(mensagem("incoming", "preparo", "5", "pending", Canal.INSTAGRAM));

        ArgumentCaptor<String> partes = ArgumentCaptor.forClass(String.class);
        verify(chatwoot, atLeast(2)).publicar(eq("8"), eq("9"), partes.capture());
        assertThat(partes.getAllValues())
                .allSatisfy(parte -> assertThat(parte.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1000));
    }

    @Test
    void messengerAceitaRespostaMaiorNumaMensagemSo() {
        String texto = "x".repeat(1800);
        when(dialogflow.detectar("cw-8-9", "oi")).thenReturn(new RespostaDialogflow("texto.saudacao", texto));

        service.receber(mensagem("incoming", "oi", "6", "pending", Canal.MESSENGER));

        verify(chatwoot).publicar("8", "9", texto);
    }

    private static MensagemEntrada mensagem(String tipo, String texto, String id, String status, Canal canal) {
        return new MensagemEntrada("message_created", tipo, false, texto, id, "8", "9", status, canal);
    }
}
