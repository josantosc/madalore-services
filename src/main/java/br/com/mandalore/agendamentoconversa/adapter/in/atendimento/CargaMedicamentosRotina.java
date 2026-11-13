package br.com.mandalore.agendamentoconversa.adapter.in.atendimento;

import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.MedicamentoRotinaUseCase;

/**
 * Na subida, grava a lista padrão de medicamentos de rotina nas clínicas habilitadas que ainda não a receberam. A
 * marca em {@code atendimento.medicamento_rotina_conta} faz a carga acontecer uma vez só por clínica; nas subidas
 * seguintes nada muda. Falha aqui não impede a aplicação de subir: a carga volta a ser tentada no primeiro acesso.
 */
@Component
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class CargaMedicamentosRotina {

    private static final Logger LOG = LoggerFactory.getLogger(CargaMedicamentosRotina.class);

    private final MedicamentoRotinaUseCase medicamentos;
    private final ClientesProperties clientes;
    private final String contaPadrao;

    public CargaMedicamentosRotina(MedicamentoRotinaUseCase medicamentos, ClientesProperties clientes,
                                   @Value("${agenda.conta-padrao:1}") String contaPadrao) {
        this.medicamentos = medicamentos;
        this.clientes = clientes;
        this.contaPadrao = contaPadrao == null || contaPadrao.isBlank() ? "1" : contaPadrao.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void carregar() {
        Set<String> contas = new TreeSet<>(clientes.clientes().keySet());
        contas.add(contaPadrao);
        int carregadas = 0;
        for (String conta : contas) {
            try {
                if (medicamentos.carregarPadraoSeNova(conta)) {
                    carregadas++;
                    LOG.info("medicamentos de rotina: lista padrão gravada na conta {}", conta);
                }
            } catch (RuntimeException ex) {
                LOG.warn("medicamentos de rotina: carga da conta {} falhou; tenta de novo no primeiro acesso: {}", conta,
                        ex.getMessage());
            }
        }
        if (carregadas == 0) {
            LOG.debug("medicamentos de rotina: nenhuma conta nova");
        }
    }
}
