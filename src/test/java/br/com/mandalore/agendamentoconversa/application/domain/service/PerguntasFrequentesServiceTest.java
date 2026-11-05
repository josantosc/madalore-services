package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.ContextoConversa;
import br.com.mandalore.agendamentoconversa.application.domain.model.FulfillmentCommand;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoOperacao;

class PerguntasFrequentesServiceTest {

    private final PerguntasFrequentesService service = new PerguntasFrequentesService(
            tema -> Optional.ofNullable(Map.of("endereco", "  Rua Exemplo, 100\n", "contato", "  ").get(tema))
    );

    @Test
    void respondeComOTextoDaClinicaEOFechamento() {
        ResultadoOperacao resultado = service.responder(comando("acao.faq.endereco"));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_FAQ");
        assertThat(resultado.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(resultado.parametros().get("texto"))
                .isEqualTo("Rua Exemplo, 100\n\n" + PerguntasFrequentesService.FECHAMENTO);
    }

    @Test
    void temaSemTextoOfereceAtendente() {
        for (String intent : new String[] {"acao.faq.estacionamento", "acao.faq.contato"}) {
            ResultadoOperacao resultado = service.responder(comando(intent));

            assertThat(resultado.codigo()).isEqualTo(CodigoNegocio.FAQ_SEM_RESPOSTA);
            assertThat(resultado.parametros().get("texto")).isEqualTo(PerguntasFrequentesService.SEM_RESPOSTA);
        }
    }

    @Test
    void devolveOMesmoContexto() {
        FulfillmentCommand command = comando("acao.faq.endereco");

        assertThat(service.responder(command).contexto()).isSameAs(command.contexto());
    }

    @Test
    void trataSoIntentsDePerguntaFrequente() {
        assertThat(PerguntasFrequentesService.trata("acao.faq.horario")).isTrue();
        assertThat(PerguntasFrequentesService.trata("acao.consulta.convenios")).isFalse();
        assertThat(PerguntasFrequentesService.trata(null)).isFalse();
    }

    private FulfillmentCommand comando(String intent) {
        return new FulfillmentCommand("projects/p/agent/sessions/cw-2-10", intent, "", Map.of(), new ContextoConversa());
    }
}
