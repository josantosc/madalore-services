package br.com.mandalore.agendamentoconversa.adapter.out.agendamentods;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.out.agendamentods.AgendamentoDsClient.IntegrarRequest;
import br.com.mandalore.agendamentoconversa.adapter.out.agendamentods.AgendamentoDsClient.PacienteRequest;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;
import feign.FeignException;

@Component
@Profile("!local-postgres")
@ConditionalOnProperty(name = "agendamento.escrita", havingValue = "agendamento-ds", matchIfMissing = true)
public class AgendamentoDsAdapter implements AgendamentoEscritaPort {

    private static final Logger LOG = LoggerFactory.getLogger(AgendamentoDsAdapter.class);
    private static final Set<String> HORARIO_INDISPONIVEL = Set.of(
            "AGENDA_OCUPADA",
            "AGENDA_NAO_ENCONTRADA",
            "MULTIPLAS_AGENDAS"
    );

    private final AgendamentoDsClient client;
    private final ConcurrentHashMap<String, ResultadoEscrita> gravados = new ConcurrentHashMap<>();

    public AgendamentoDsAdapter(AgendamentoDsClient client) {
        this.client = client;
    }

    @Override
    public ResultadoEscrita agendar(PedidoAgendar pedido) {
        if (pedido.semCadastro()) {
            LOG.warn("agendamento sem cadastro ainda nao suportado pelo agendamento-ds");
            return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
        }
        if (pedido.isExame()) {
            // O agendamento-ds exige CD_INTEGRACAO no PROC_INTERNO, ausente nos exames do Tasy.
            LOG.warn("agendamento de exame ainda nao suportado pelo agendamento-ds proc_interno={}",
                    pedido.exame().nrSeqProcInterno());
            return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
        }
        ResultadoEscrita anterior = gravados.get(pedido.chaveIdempotencia());
        if (anterior != null) {
            return anterior;
        }
        try {
            client.integrar(new IntegrarRequest(
                    "C",
                    new BigDecimal(pedido.cdAgenda()),
                    pedido.dataAgenda(),
                    new PacienteRequest(pedido.cpf(), pedido.nome(), pedido.nascimento())
            ));
            ResultadoEscrita sucesso = new ResultadoEscrita(CodigoNegocio.SUCESSO);
            gravados.put(pedido.chaveIdempotencia(), sucesso);
            return sucesso;
        } catch (AgendamentoDsException ex) {
            return mapear(ex.getCodigo());
        } catch (FeignException ex) {
            LOG.warn("agendamento-ds status={}", ex.status());
            return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
        }
    }

    @Override
    public ResultadoEscrita confirmar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        LOG.info("confirmar sem metodo no agendamento-ds");
        return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
    }

    @Override
    public ResultadoEscrita cancelar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        LOG.info("cancelar sem metodo no agendamento-ds");
        return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
    }

    @Override
    public ResultadoEscrita remarcar(String chaveIdempotencia, String pacienteId, String nrAtual, PedidoAgendar novoHorario) {
        LOG.info("remarcar sem cancelamento no agendamento-ds");
        return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
    }

    private ResultadoEscrita mapear(String codigo) {
        if (HORARIO_INDISPONIVEL.contains(codigo)) {
            return new ResultadoEscrita(CodigoNegocio.HORARIO_INDISPONIVEL);
        }
        return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
    }
}
