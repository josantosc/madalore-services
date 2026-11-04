package br.com.mandalore.agendamentoconversa.application.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.RespostaDialogflow;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.SimularConversaUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.DialogflowPort;

@Service
@ConditionalOnProperty(name = "simulador.enabled", havingValue = "true")
public class SimuladorService implements SimularConversaUseCase {

    static final String SEM_TEXTO = "Consigo seguir por texto. Pode digitar a opção?";

    private static final Logger LOG = LoggerFactory.getLogger(SimuladorService.class);

    private final DialogflowPort dialogflow;

    public SimuladorService(DialogflowPort dialogflow) {
        this.dialogflow = dialogflow;
    }

    @Override
    public RespostaDialogflow enviar(String sessao, String texto) {
        if (texto == null || texto.isBlank()) {
            return new RespostaDialogflow("", SEM_TEXTO);
        }
        String sessionId = Textos.sessionId("sim", sessao);
        long inicio = System.nanoTime();
        RespostaDialogflow resposta = dialogflow.detectar(sessionId, texto);
        LOG.info(
                "simulador session={} intent={} latencia_ms={}",
                sessionId,
                resposta.intent(),
                (System.nanoTime() - inicio) / 1_000_000
        );
        return resposta;
    }
}
