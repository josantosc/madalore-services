package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import br.com.mandalore.agendamentoconversa.application.domain.model.Anexo;
import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.LeitorPedidoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.TabelaPrecoPort;

class OrcamentoPedidoServiceTest {

    private static final String URL = "https://chat/rails/active_storage/blobs/redirect/x/pedido.jpg";
    private static final Orcamento.Arquivo FOTO = new Orcamento.Arquivo(new byte[] {1, 2}, "image/jpeg");

    private ChatwootPort chatwoot;
    private LeitorPedidoPort leitor;
    private TabelaPrecoPort tabela;
    private OrcamentoPedidoService service;

    @BeforeEach
    void setUp() {
        chatwoot = mock(ChatwootPort.class);
        leitor = mock(LeitorPedidoPort.class);
        tabela = mock(TabelaPrecoPort.class);
        service = new OrcamentoPedidoService(chatwoot, leitor, tabela, Runnable::run);
        when(tabela.itens()).thenReturn(List.of(
                new Orcamento.ItemPreco("HMG", "Hemograma completo", List.of(), new BigDecimal("25"), "")));
        when(chatwoot.baixar(URL)).thenReturn(FOTO);
    }

    @Test
    void respondePacienteDeixaNotaEPassaParaFaturamento() {
        when(leitor.ler(FOTO, List.of("Hemograma completo"))).thenReturn(new Orcamento.PedidoLido(true,
                List.of(new Orcamento.ExameLido("HMG", "Hemograma completo"))));

        service.orcar(mensagem());

        InOrder ordem = inOrder(chatwoot);
        ordem.verify(chatwoot).publicar("1", "7", OrcamentoPedidoService.RECEBIDO);
        ordem.verify(chatwoot).notaPrivada(eq("1"), eq("7"), contains("- Hemograma completo: R$ 25,00"));
        ordem.verify(chatwoot).atribuirEquipe("1", "7", Setor.FATURAMENTO);
        ordem.verify(chatwoot).abrirAtendente("1", "7");
    }

    @Test
    void imagemQueNaoEPedidoVaiParaRecepcao() {
        when(leitor.ler(any(), anyList())).thenReturn(new Orcamento.PedidoLido(false, List.of()));

        service.orcar(mensagem());

        verify(chatwoot).notaPrivada("1", "7", NotaOrcamento.NAO_E_PEDIDO);
        verify(chatwoot).atribuirEquipe("1", "7", Setor.RECEPCAO);
    }

    @Test
    void falhaNaLeituraAindaPassaParaAtendente() {
        when(leitor.ler(any(), anyList())).thenThrow(new IllegalStateException("403"));
        doThrow(new IllegalStateException("500")).when(chatwoot).notaPrivada("1", "7", NotaOrcamento.FALHA);

        service.orcar(mensagem());

        verify(chatwoot).atribuirEquipe("1", "7", Setor.FATURAMENTO);
        verify(chatwoot).abrirAtendente("1", "7");
    }

    private static MensagemEntrada mensagem() {
        return new MensagemEntrada("message_created", "incoming", false, null, "m1", "1", "7", "pending",
                Canal.WHATSAPP, List.of(new Anexo("image", URL)));
    }
}
