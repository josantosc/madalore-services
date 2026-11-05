package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.ConversaVisitante;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.SimularChatwootUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.ChatwootVisitantePort;

@Service
@ConditionalOnProperty(name = "simulador.enabled", havingValue = "true")
public class SimuladorChatwootService implements SimularChatwootUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(SimuladorChatwootService.class);

    private final ChatwootVisitantePort chatwoot;
    private final Map<String, String> visitantes = new ConcurrentHashMap<>();

    public SimuladorChatwootService(ChatwootVisitantePort chatwoot) {
        this.chatwoot = chatwoot;
    }

    @Override
    public ConversaVisitante enviar(String sessao, String texto) {
        String token = visitantes.computeIfAbsent(sessao, s -> chatwoot.criarVisitante());
        chatwoot.enviar(token, texto);
        ConversaVisitante conversa = chatwoot.ler(token);
        LOG.info("simulador chatwoot session={} conversation_id={} status={}",
                sessao, conversa.conversationId(), conversa.status());
        return conversa;
    }

    @Override
    public ConversaVisitante enviarArquivo(String sessao, ConversaVisitante.Arquivo arquivo, String legenda) {
        String token = visitantes.computeIfAbsent(sessao, s -> chatwoot.criarVisitante());
        chatwoot.enviarArquivo(token, arquivo, legenda);
        ConversaVisitante conversa = chatwoot.ler(token);
        LOG.info("simulador chatwoot arquivo session={} conversation_id={} tipo={} bytes={}",
                sessao, conversa.conversationId(), arquivo.tipo(), arquivo.conteudo().length);
        return conversa;
    }

    @Override
    public ConversaVisitante ler(String sessao) {
        String token = visitantes.get(sessao);
        if (token == null) {
            return new ConversaVisitante(null, null, List.of());
        }
        return chatwoot.ler(token);
    }
}
