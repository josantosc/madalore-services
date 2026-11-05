package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.FulfillmentCommand;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoOperacao;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.PerguntasFrequentesPort;

/**
 * Responde as intents {@code acao.faq.<tema>} com o texto da clínica. O contexto volta intacto, para a
 * pergunta no meio de um agendamento não perder o passo em que o paciente estava.
 */
@Service
public class PerguntasFrequentesService {

    static final String PREFIXO = "acao.faq.";
    static final String EVENTO = "RESULTADO_FAQ";
    static final String FECHAMENTO = "Posso ajudar em algo mais? Para marcar consulta ou exame, digite menu.";
    static final String SEM_RESPOSTA =
            "Essa informação eu não tenho por aqui. Se quiser, diga atendente para falar com alguém da equipe.";

    private final PerguntasFrequentesPort respostas;

    public PerguntasFrequentesService(PerguntasFrequentesPort respostas) {
        this.respostas = respostas;
    }

    public static boolean trata(String intent) {
        return intent != null && intent.startsWith(PREFIXO);
    }

    public ResultadoOperacao responder(FulfillmentCommand command) {
        return respostas.resposta(command.operacao())
                .map(String::strip)
                .filter(texto -> !texto.isEmpty())
                .map(texto -> new ResultadoOperacao(
                        EVENTO,
                        Map.of("texto", texto + "\n\n" + FECHAMENTO),
                        command.contexto(),
                        CodigoNegocio.SUCESSO
                ))
                .orElseGet(() -> new ResultadoOperacao(
                        EVENTO,
                        Map.of("texto", SEM_RESPOSTA),
                        command.contexto(),
                        CodigoNegocio.FAQ_SEM_RESPOSTA
                ));
    }
}
