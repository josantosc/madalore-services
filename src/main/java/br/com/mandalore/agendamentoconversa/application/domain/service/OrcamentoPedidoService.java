package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Anexo;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.model.Setor;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;
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
    private static final int LIMITE_COMANDOS = 10_000;

    private final ChatwootPort chatwoot;
    private final LeitorPedidoPort leitor;
    private final TabelaPrecoPort tabela;
    private final Executor executor;
    private final Supplier<FunilUseCase> funil;
    private final Set<String> comandosVistos = ConcurrentHashMap.newKeySet();

    @Autowired
    public OrcamentoPedidoService(ChatwootPort chatwoot, LeitorPedidoPort leitor, TabelaPrecoPort tabela,
                                  ObjectProvider<FunilUseCase> funil) {
        this(chatwoot, leitor, tabela, Executors.newVirtualThreadPerTaskExecutor(), funil::getIfAvailable);
    }

    OrcamentoPedidoService(ChatwootPort chatwoot, LeitorPedidoPort leitor, TabelaPrecoPort tabela, Executor executor) {
        this(chatwoot, leitor, tabela, executor, () -> null);
    }

    OrcamentoPedidoService(ChatwootPort chatwoot, LeitorPedidoPort leitor, TabelaPrecoPort tabela, Executor executor,
                           Supplier<FunilUseCase> funil) {
        this.chatwoot = chatwoot;
        this.leitor = leitor;
        this.tabela = tabela;
        this.executor = executor;
        this.funil = funil;
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
        BigDecimal total = null;
        Integer exames = null;
        try {
            List<Orcamento.ItemPreco> itens = tabela.itens();
            Orcamento.PedidoLido pedido = leitor.ler(
                    chatwoot.baixar(anexo.url()),
                    itens.stream().map(Orcamento.ItemPreco::nome).toList());
            List<Orcamento.Linha> linhas = NotaOrcamento.casar(pedido, itens);
            nota = NotaOrcamento.nota(pedido, linhas);
            if (!pedido.pedidoMedico()) {
                setor = Setor.RECEPCAO;
            } else if (!linhas.isEmpty()) {
                total = linhas.stream().filter(l -> l.item() != null).map(l -> l.item().valor())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                exames = linhas.size();
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
        FunilUseCase oportunidades = funil.get();
        if (oportunidades != null && setor == Setor.FATURAMENTO) {
            BigDecimal valor = total;
            Integer quantidade = exames;
            tentar(conversationId, "funil", () -> oportunidades.abrirOrcamento(accountId, conversationId, valor, quantidade));
        }
        LOG.info("conversation_id={} orcamento_pedido {} equipe={} latencia_ms={}",
                conversationId, resultado, setor, (System.nanoTime() - inicio) / 1_000_000);
    }

    @Override
    public void orcar(Orcamento.Comando comando) {
        if (comando.mensagem() != null) {
            if (comandosVistos.size() >= LIMITE_COMANDOS) {
                comandosVistos.clear();
            }
            if (!comandosVistos.add(comando.conta() + ":" + comando.mensagem())) {
                return;
            }
        }
        executor.execute(() -> calcular(comando));
    }

    void calcular(Orcamento.Comando comando) {
        String conversa = comando.conversa();
        List<Orcamento.Linha> linhas = List.of();
        String nota;
        try {
            List<Orcamento.ItemPreco> itens = tabela.itens();
            linhas = NotaOrcamento.casarTexto(NotaOrcamento.termos(comando.exames()), itens);
            nota = NotaOrcamento.notaComando(linhas, itens);
        } catch (RuntimeException ex) {
            nota = NotaOrcamento.COMANDO_FALHA;
            LOG.warn("conversation_id={} orcamento_comando tabela falhou: {}", conversa, ex.getClass().getSimpleName());
        }
        String texto = nota;
        tentar(conversa, "nota", () -> chatwoot.notaPrivada(comando.conta(), conversa, texto));
        List<Orcamento.Linha> comPreco = linhas.stream().filter(l -> l.item() != null).toList();
        FunilUseCase oportunidades = funil.get();
        if (oportunidades != null && !comPreco.isEmpty()) {
            BigDecimal total = comPreco.stream().map(l -> l.item().valor()).reduce(BigDecimal.ZERO, BigDecimal::add);
            int quantidade = linhas.size();
            tentar(conversa, "funil", () -> oportunidades.orcamentoCalculado(comando.conta(), conversa, total,
                    quantidade, comando.atendente()));
        }
        LOG.info("conversation_id={} orcamento_comando exames={} sem_preco={}",
                conversa, linhas.size(), linhas.size() - comPreco.size());
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
