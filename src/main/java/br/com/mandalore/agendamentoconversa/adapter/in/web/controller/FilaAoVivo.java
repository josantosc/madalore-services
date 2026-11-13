package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
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

    private final Map<String, Set<SseEmitter>> porConta = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ping = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fila-ao-vivo");
        t.setDaemon(true);
        return t;
    });

    public FilaAoVivo() {
        ping.scheduleAtFixedRate(this::pingar, PING_SEGUNDOS, PING_SEGUNDOS, TimeUnit.SECONDS);
    }

    public SseEmitter assinar(String conta) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> conectados = porConta.computeIfAbsent(conta, k -> ConcurrentHashMap.newKeySet());
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
        if (conectados == null) {
            return;
        }
        for (SseEmitter emitter : conectados) {
            enviar(conectados, emitter, SseEmitter.event().name("fila").data(aviso, MediaType.APPLICATION_JSON));
        }
    }

    int conectados(String conta) {
        Set<SseEmitter> conectados = porConta.get(conta);
        return conectados == null ? 0 : conectados.size();
    }

    private void pingar() {
        porConta.values().forEach(conectados -> conectados.forEach(
                emitter -> enviar(conectados, emitter, SseEmitter.event().comment("ping"))));
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
        porConta.values().forEach(conectados -> conectados.forEach(emitter -> {
            try {
                emitter.complete();
            } catch (IllegalStateException ignorado) {
                // já encerrado
            }
        }));
        porConta.clear();
    }
}
