package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Anexo;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.OrcarPedidoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.LeitorPedidoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.TabelaPrecoPort;

@Service
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class OrcamentoPedidoService implements OrcarPedidoUseCase {

    static final String RECEBIDO = "Recebi o seu pedido. Vou conferir os exames e um atendente "
            + "te passa o orçamento por aqui em instantes.";

    private static final Logger LOG = LoggerFactory.getLogger(OrcamentoPedidoService.class);

    private final ChatwootPort chatwoot;
    private final LeitorPedidoPort leitor;
    private final TabelaPrecoPort tabela;
    private final Executor executor;

    @Autowired
    public OrcamentoPedidoService(ChatwootPort chatwoot, LeitorPedidoPort leitor, TabelaPrecoPort tabela) {
        this(chatwoot, leitor, tabela, Executors.newVirtualThreadPerTaskExecutor());
    }

    OrcamentoPedidoService(ChatwootPort chatwoot, LeitorPedidoPort leitor, TabelaPrecoPort tabela, Executor executor) {
        this.chatwoot = chatwoot;
        this.leitor = leitor;
        this.tabela = tabela;
        this.executor = executor;
    }

    @Override
    public void orcar(MensagemEntrada mensagem) {
        Anexo anexo = mensagem.documento().orElseThrow(() -> new IllegalArgumentException("Mensagem sem pedido anexo"));
        chatwoot.publicar(mensagem.accountId(), mensagem.conversationId(), RECEBIDO);
        executor.execute(() -> processar(mensagem, anexo));
    }

    void processar(MensagemEntrada mensagem, Anexo anexo) {
        long inicio = System.nanoTime();
        String nota;
        Setor setor = Setor.FATURAMENTO;
        String resultado;
        try {
            List<Orcamento.ItemPreco> itens = tabela.itens();
            Orcamento.PedidoLido pedido = leitor.ler(
                    chatwoot.baixar(anexo.url()),
                    itens.stream().map(Orcamento.ItemPreco::nome).toList());
            List<Orcamento.Linha> linhas = NotaOrcamento.casar(pedido, itens);
            nota = NotaOrcamento.nota(pedido, linhas);
            if (!pedido.pedidoMedico()) {
                setor = Setor.RECEPCAO;
            }
            long semPreco = linhas.stream().filter(l -> l.item() == null).count();
            resultado = "exames=" + linhas.size() + " sem_preco=" + semPreco + " pedido_medico=" + pedido.pedidoMedico();
        } catch (RuntimeException ex) {
            nota = NotaOrcamento.FALHA;
            resultado = "falha=" + ex.getClass().getSimpleName();
            LOG.warn("conversation_id={} orcamento_pedido leitura falhou: {}", mensagem.conversationId(), ex.getMessage());
        }
        String accountId = mensagem.accountId();
        String conversationId = mensagem.conversationId();
        String texto = nota;
        tentar(conversationId, "nota", () -> chatwoot.notaPrivada(accountId, conversationId, texto));
        Setor equipe = setor;
        tentar(conversationId, "equipe", () -> chatwoot.atribuirEquipe(accountId, conversationId, equipe));
        tentar(conversationId, "abrir", () -> chatwoot.abrirAtendente(accountId, conversationId));
        LOG.info("conversation_id={} orcamento_pedido {} equipe={} latencia_ms={}",
                conversationId, resultado, setor, (System.nanoTime() - inicio) / 1_000_000);
    }

    private static void tentar(String conversationId, String etapa, Runnable acao) {
        try {
            acao.run();
        } catch (RuntimeException ex) {
            LOG.warn("conversation_id={} orcamento_pedido etapa={} falhou: {}",
                    conversationId, etapa, ex.getClass().getSimpleName());
        }
    }
}
