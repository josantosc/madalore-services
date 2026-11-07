package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import br.com.mandalore.agendamentoconversa.application.domain.model.Anexo;
import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.OrcarPedidoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;

@ExtendWith(MockitoExtension.class)
class MensagemChatwootServiceTest {

    @Mock
    private DialogflowPort dialogflow;

    @Mock
    private ChatwootPort chatwoot;

    @Mock
    private ObjectProvider<OrcarPedidoUseCase> orcamentos;

    @Mock
    private OrcarPedidoUseCase orcamento;

    @Mock
    private FlowTelemetry telemetry;

    @InjectMocks
    private MensagemChatwootService service;

    @Test
    void fotoDoPedidoVaiParaOrcamento() {
        when(orcamentos.getIfAvailable()).thenReturn(orcamento);
        MensagemEntrada foto = comFoto("legenda");

        service.receber(foto);

        verify(orcamento).orcar(foto);
        verify(dialogflow, never()).detectar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<FlowEvent> evento = ArgumentCaptor.forClass(FlowEvent.class);
        verify(telemetry).record(evento.capture());
        assertThat(evento.getValue().stage()).isEqualTo("orcamento.pedido");
    }

    @Test
    void fotoComOrcamentoDesligadoPedeTexto() {
        service.receber(comFoto(null));

        verify(chatwoot).publicar("8", "9", MensagemChatwootService.SEM_TEXTO);
    }

    private static MensagemEntrada comFoto(String legenda) {
        return new MensagemEntrada("message_created", "incoming", false, legenda, "20", "8", "9", "pending",
                Canal.WHATSAPP, java.util.List.of(new Anexo("image", "https://chat/foto.jpg")));
    }

    @Test
    void perguntaDeValoresComExameDaTabelaVaiParaOrcamentoSemATextoDaFaq() {
        when(orcamentos.getIfAvailable()).thenReturn(orcamento);
        MensagemEntrada pergunta = mensagem("incoming", "quanto custa hemograma?", "30", "pending", Canal.WHATSAPP);
        when(dialogflow.detectar("cw-8-9", "quanto custa hemograma?"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_VALORES, "Mande a foto do pedido."));
        when(orcamento.orcarTexto(pergunta)).thenReturn(true);

        service.receber(pergunta);

        verify(chatwoot, never()).publicar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<FlowEvent> evento = ArgumentCaptor.forClass(FlowEvent.class);
        verify(telemetry).record(evento.capture());
        assertThat(evento.getValue().stage()).isEqualTo("orcamento.texto");
    }

    @Test
    void depoisDaRespostaDeValoresAProximaMensagemPodeTrazerOsExames() {
        when(orcamentos.getIfAvailable()).thenReturn(orcamento);
        MensagemEntrada pergunta = mensagem("incoming", "quanto custa?", "31", "pending", Canal.WHATSAPP);
        MensagemEntrada exames = mensagem("incoming", "hemograma e tsh", "32", "pending", Canal.WHATSAPP);
        when(dialogflow.detectar("cw-8-9", "quanto custa?"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_VALORES, "Mande a foto ou escreva os exames."));
        when(orcamento.orcarTexto(pergunta)).thenReturn(false);
        when(orcamento.orcarTexto(exames)).thenReturn(true);

        service.receber(pergunta);
        service.receber(exames);

        verify(chatwoot).publicar("8", "9", "Mande a foto ou escreva os exames.");
        verify(dialogflow, never()).detectar("cw-8-9", "hemograma e tsh");
    }

    @Test
    void respostaDeValoresSemConviteGanhaADicaDeEscreverOsExames() {
        when(orcamentos.getIfAvailable()).thenReturn(orcamento);
        String faq = "Os valores dependem dos exames do pedido. Mande aqui uma foto do pedido médico.\n\n"
                + PerguntasFrequentesService.FECHAMENTO;
        when(dialogflow.detectar("cw-8-9", "quanto custa?"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_VALORES, faq));

        service.receber(mensagem("incoming", "quanto custa?", "34", "pending", Canal.WHATSAPP));

        verify(chatwoot).publicar("8", "9", "Os valores dependem dos exames do pedido. Mande aqui uma foto do pedido médico."
                + "\n\n" + MensagemChatwootService.DICA_EXAMES + "\n\n" + PerguntasFrequentesService.FECHAMENTO);
    }

    @Test
    void semPedidoOAssistentePedeOsNomesEContinuaEsperando() {
        when(orcamentos.getIfAvailable()).thenReturn(orcamento);
        MensagemEntrada semPedido = mensagem("incoming", "não tenho o pedido médico", "36", "pending", Canal.WHATSAPP);
        MensagemEntrada exames = mensagem("incoming", "hemograma e glicose", "37", "pending", Canal.WHATSAPP);
        when(dialogflow.detectar("cw-8-9", "quanto custa?"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_VALORES, "Escreva os exames."));
        when(orcamento.orcarTexto(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(orcamento.orcarTexto(exames)).thenReturn(true);

        service.receber(mensagem("incoming", "quanto custa?", "35", "pending", Canal.WHATSAPP));
        service.receber(semPedido);
        service.receber(exames);

        verify(chatwoot).publicar("8", "9", MensagemChatwootService.SEM_PEDIDO);
        verify(orcamento).orcarTexto(exames);
        verify(dialogflow, never()).detectar("cw-8-9", "não tenho o pedido médico");
    }

    @Test
    void exameForaDaTabelaDuranteAEsperaVaiParaOFaturamento() {
        when(orcamentos.getIfAvailable()).thenReturn(orcamento);
        when(dialogflow.detectar("cw-8-9", "quanto custa?"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_VALORES, "Escreva os exames."));
        when(dialogflow.detectar("cw-8-9", "cintilografia"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_FALLBACK, "Não entendi."));

        service.receber(mensagem("incoming", "quanto custa?", "38", "pending", Canal.WHATSAPP));
        service.receber(mensagem("incoming", "cintilografia", "39", "pending", Canal.WHATSAPP));

        verify(chatwoot).publicar("8", "9", MensagemChatwootService.EXAMES_NAO_ACHADOS);
        verify(chatwoot, never()).publicar("8", "9", "Não entendi.");
        verify(chatwoot).atribuirEquipe("8", "9", Setor.FATURAMENTO);
        verify(chatwoot).abrirAtendente("8", "9");
    }

    @Test
    void opcaoOrcamentoDoMenuEsperaOsExames() {
        when(orcamentos.getIfAvailable()).thenReturn(orcamento);
        MensagemEntrada exames = mensagem("incoming", "hemograma", "41", "pending", Canal.WHATSAPP);
        when(dialogflow.detectar("cw-8-9", "4"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_MENU_ORCAMENTO + ".retomar",
                        "Mande a foto ou escreva os exames."));
        when(orcamento.orcarTexto(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(orcamento.orcarTexto(exames)).thenReturn(true);

        service.receber(mensagem("incoming", "4", "40", "pending", Canal.WHATSAPP));
        service.receber(exames);

        verify(chatwoot).publicar("8", "9", "Mande a foto ou escreva os exames.");
        verify(dialogflow, never()).detectar("cw-8-9", "hemograma");
    }

    @Test
    void pacienteEncerraAConversaEOChatwootResolve() {
        when(dialogflow.detectar("cw-8-9", "encerrar"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_ENCERRAR, "Atendimento encerrado."));
        when(dialogflow.detectar("cw-8-9", "2"))
                .thenReturn(new RespostaDialogflow(MensagemChatwootService.INTENT_ENCERRAR + ".pos_agendamento",
                        "Atendimento encerrado."));

        service.receber(mensagem("incoming", "encerrar", "42", "pending", Canal.WHATSAPP));
        service.receber(mensagem("incoming", "2", "43", "pending", Canal.WHATSAPP));

        verify(chatwoot, org.mockito.Mockito.times(2)).publicar("8", "9", "Atendimento encerrado.");
        verify(chatwoot, org.mockito.Mockito.times(2)).resolverSePendente("8", "9");
    }

    @Test
    void agradecimentoNaoEncerraAConversa() {
        when(dialogflow.detectar("cw-8-9", "obrigado"))
                .thenReturn(new RespostaDialogflow("texto.conversa.agradecimento", "Por nada!"));

        service.receber(mensagem("incoming", "obrigado", "44", "pending", Canal.WHATSAPP));

        verify(chatwoot).publicar("8", "9", "Por nada!");
        verify(chatwoot, never()).resolverSePendente("8", "9");
    }

    @Test
    void reconheceQuemDizQueNaoTemOPedido() {
        assertThat(MensagemChatwootService.semPedido("não tenho o pedido")).isTrue();
        assertThat(MensagemChatwootService.semPedido("nao tenho pedido medico")).isTrue();
        assertThat(MensagemChatwootService.semPedido("Estou sem a guia")).isTrue();
        assertThat(MensagemChatwootService.semPedido("o médico não deu receita")).isTrue();
        assertThat(MensagemChatwootService.semPedido("hemograma e glicose")).isFalse();
        assertThat(MensagemChatwootService.semPedido("mandei o pedido")).isFalse();
    }

    @Test
    void mensagemSemPerguntaDeValoresNaoProcuraExames() {
        when(orcamentos.getIfAvailable()).thenReturn(orcamento);
        when(dialogflow.detectar("cw-8-9", "hemograma")).thenReturn(new RespostaDialogflow("texto.fallback", "Não entendi."));

        service.receber(mensagem("incoming", "hemograma", "33", "pending", Canal.WHATSAPP));

        verify(orcamento, never()).orcarTexto(org.mockito.ArgumentMatchers.any());
        verify(chatwoot).publicar("8", "9", "Não entendi.");
    }

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
        InOrder ordem = inOrder(chatwoot);
        ordem.verify(chatwoot).atribuirEquipe("8", "9", Setor.RECEPCAO);
        ordem.verify(chatwoot).abrirAtendente("8", "9");
    }

    @Test
    void transbordoUsaAssuntoDaMensagemAtual() {
        when(dialogflow.detectar("cw-8-9", "quero falar com alguém sobre a guia do convênio"))
                .thenReturn(new RespostaDialogflow("transbordo.atendente", "Vou chamar alguém da equipe."));

        service.receber(mensagem("incoming", "quero falar com alguém sobre a guia do convênio", "7", "pending", Canal.SITE));

        verify(chatwoot).atribuirEquipe("8", "9", Setor.FATURAMENTO);
    }

    @Test
    void transbordoLembraAssuntoDeMensagemAnterior() {
        when(dialogflow.detectar("cw-8-9", "cadê o resultado do meu exame?"))
                .thenReturn(new RespostaDialogflow("texto.fallback", "Não entendi."));
        when(dialogflow.detectar("cw-8-9", "atendente"))
                .thenReturn(new RespostaDialogflow("transbordo.atendente", "Vou chamar alguém da equipe."));

        service.receber(mensagem("incoming", "cadê o resultado do meu exame?", "10", "pending", Canal.WHATSAPP));
        service.receber(mensagem("incoming", "atendente", "11", "pending", Canal.WHATSAPP));

        verify(chatwoot).atribuirEquipe("8", "9", Setor.LABORATORIO);
        verify(chatwoot).abrirAtendente("8", "9");
        ArgumentCaptor<FlowEvent> evento = ArgumentCaptor.forClass(FlowEvent.class);
        verify(telemetry).record(evento.capture());
        assertThat(evento.getValue().component()).isEqualTo("CHATWOOT");
        assertThat(evento.getValue().stage()).isEqualTo("transbordo");
        assertThat(evento.getValue().details()).containsEntry("equipe", "LABORATORIO");
    }

    @Test
    void falhaAoAtribuirEquipeAindaAbreConversa() {
        when(dialogflow.detectar("cw-8-9", "atendente"))
                .thenReturn(new RespostaDialogflow("transbordo.atendente", "Vou chamar alguém da equipe."));
        when(chatwoot.atribuirEquipe("8", "9", Setor.RECEPCAO)).thenThrow(new IllegalStateException("404"));

        service.receber(mensagem("incoming", "atendente", "12", "pending", Canal.SITE));

        verify(chatwoot).abrirAtendente("8", "9");
        verify(chatwoot, never()).publicar("8", "9", MensagemChatwootService.FALHA);
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
