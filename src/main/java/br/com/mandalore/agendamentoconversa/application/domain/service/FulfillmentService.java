package br.com.mandalore.agendamentoconversa.application.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.FulfillmentCommand;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoOperacao;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ExecutarFulfillmentUseCase;

@Service
public class FulfillmentService implements ExecutarFulfillmentUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(FulfillmentService.class);
    private static final String ESCRITA = "RESULTADO_SUCESSO_ESCRITA";

    private final AgendaConsultaService agenda;
    private final PerguntasFrequentesService perguntas;
    private final FlowTelemetry telemetry;

    public FulfillmentService(
            AgendaConsultaService agenda,
            PerguntasFrequentesService perguntas,
            FlowTelemetry telemetry
    ) {
        this.agenda = agenda;
        this.perguntas = perguntas;
        this.telemetry = telemetry;
    }

    @Override
    public ResultadoOperacao executar(FulfillmentCommand command) {
        long inicio = System.nanoTime();
        String status = "sucesso";
        ResultadoOperacao resultado = null;
        try {
            if (!command.acao()) {
                status = "falha";
                resultado = new ResultadoOperacao(
                        "RESULTADO_INDISPONIVEL",
                        java.util.Map.of(),
                        command.contexto(),
                        CodigoNegocio.INDISPONIVEL
                );
            } else if (PerguntasFrequentesService.trata(command.intent())) {
                resultado = perguntas.responder(command);
            } else {
                resultado = agenda.executar(command);
            }
            return resultado;
        } catch (RuntimeException ex) {
            status = "falha";
            LOG.warn(
                    "conversation_id={} intent={} fulfillment_falhou",
                    Textos.conversationId(command.session()),
                    command.intent(),
                    ex
            );
            String component = ex instanceof DataAccessException ? "POSTGRES" : "MANDALORE";
            telemetry.record(FlowEvent.builder(component, "fulfillment." + command.operacao(), "ERROR")
                    .correlation(
                            Textos.conversationId(command.session()),
                            Textos.conversationId(command.session())
                    )
                    .intent(command.intent(), command.operacao())
                    .error("FULFILLMENT_FAILED", ex)
                    .durationMs(elapsed(inicio))
                    .build());
            resultado = new ResultadoOperacao(
                    "RESULTADO_INDISPONIVEL",
                    java.util.Map.of(),
                    command.contexto(),
                    CodigoNegocio.INDISPONIVEL
            );
            return resultado;
        } finally {
            String codigo = resultado == null ? CodigoNegocio.INDISPONIVEL.valor() : resultado.codigo().valor();
            FlowEvent.Builder evento = FlowEvent.builder("MANDALORE", "fulfillment." + command.operacao(), status.equals("sucesso") ? "SUCCESS" : "ERROR")
                    .correlation(
                            Textos.conversationId(command.session()),
                            Textos.conversationId(command.session())
                    )
                    .intent(command.intent(), command.operacao())
                    .eventName(resultado == null ? null : resultado.evento())
                    .businessResult(codigo)
                    .durationMs(elapsed(inicio));
            if (resultado != null && ESCRITA.equals(resultado.evento())) {
                evento.detail("escrita", resultado.parametros().get("acao"))
                        .detail("exame", resultado.contexto() != null && resultado.contexto().isExame());
            }
            telemetry.record(evento.build());
            LOG.info(
                    "conversation_id={} intent={} codigo={} latencia_ms={} status={}",
                    Textos.conversationId(command.session()),
                    command.intent(),
                    codigo,
                    elapsed(inicio),
                    status
            );
        }
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
