package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import br.com.mandalore.agendamentoconversa.application.domain.port.out.AvisoFilaPort;
import jakarta.annotation.PreDestroy;

/**
 * Server-Sent Events das telas /atendimento e /consultorio, por clínica. Vale para uma instância da aplicação: com
 * várias, cada tela recebe só os avisos da instância em que está conectada e o recarregamento periódico cobre o resto.
 */
@Component
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class FilaAoVivo implements AvisoFilaPort {

    static final long TIMEOUT_MS = 30 * 60_000L;
    /** Abaixo do tempo ocioso padrão dos proxies (60 s), para a conexão não cair sem aviso. */
    private static final long PING_SEGUNDOS = 25;

    static final String CHAMADO = "CHAMADO";

    private final Map<String, Set<SseEmitter>> porConta = new ConcurrentHashMap<>();
    /** Painel público da sala de espera: recebe só o sinal de nova chamada, sem ids. */
    private final Map<String, Set<SseEmitter>> painelPorConta = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ping = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fila-ao-vivo");
        t.setDaemon(true);
        return t;
    });

    public FilaAoVivo() {
        ping.scheduleAtFixedRate(this::pingar, PING_SEGUNDOS, PING_SEGUNDOS, TimeUnit.SECONDS);
    }

    public SseEmitter assinar(String conta) {
        return assinar(porConta, conta);
    }

    public SseEmitter assinarPainel(String conta) {
        return assinar(painelPorConta, conta);
    }

    private static SseEmitter assinar(Map<String, Set<SseEmitter>> mapa, String conta) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> conectados = mapa.computeIfAbsent(conta, k -> ConcurrentHashMap.newKeySet());
        conectados.add(emitter);
        Runnable sair = () -> conectados.remove(emitter);
        emitter.onCompletion(sair);
        emitter.onTimeout(sair);
        emitter.onError(erro -> sair.run());
        enviar(conectados, emitter, SseEmitter.event().comment("conectado"));
        return emitter;
    }

    @Override
    public void publicar(String conta, Aviso aviso) {
        Set<SseEmitter> conectados = porConta.get(conta);
        if (conectados != null) {
            for (SseEmitter emitter : conectados) {
                enviar(conectados, emitter, SseEmitter.event().name("fila").data(aviso, MediaType.APPLICATION_JSON));
            }
        }
        Set<SseEmitter> paineis = painelPorConta.get(conta);
        if (paineis != null && aviso != null && CHAMADO.equals(aviso.tipo())) {
            for (SseEmitter emitter : paineis) {
                enviar(paineis, emitter, SseEmitter.event().name("chamada").data("{}"));
            }
        }
    }

    int conectados(String conta) {
        Set<SseEmitter> conectados = porConta.get(conta);
        return conectados == null ? 0 : conectados.size();
    }

    private void pingar() {
        for (Map<String, Set<SseEmitter>> mapa : List.of(porConta, painelPorConta)) {
            mapa.values().forEach(conectados -> conectados.forEach(
                    emitter -> enviar(conectados, emitter, SseEmitter.event().comment("ping"))));
        }
    }

    /** Conexão que falhou sai da lista; o EventSource do navegador reconecta sozinho. */
    private static void enviar(Set<SseEmitter> conectados, SseEmitter emitter, SseEmitter.SseEventBuilder evento) {
        try {
            emitter.send(evento);
        } catch (IOException | IllegalStateException ex) {
            conectados.remove(emitter);
            try {
                emitter.complete();
            } catch (IllegalStateException ignorado) {
                // já encerrado
            }
        }
    }

    @PreDestroy
    void parar() {
        ping.shutdownNow();
        for (Map<String, Set<SseEmitter>> mapa : List.of(porConta, painelPorConta)) {
            mapa.values().forEach(conectados -> conectados.forEach(emitter -> {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignorado) {
                    // já encerrado
                }
            }));
            mapa.clear();
        }
    }
}
