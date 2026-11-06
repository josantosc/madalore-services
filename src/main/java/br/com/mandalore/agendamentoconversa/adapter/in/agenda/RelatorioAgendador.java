package br.com.mandalore.agendamentoconversa.adapter.in.agenda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.RelatorioUseCase;

/**
 * Envia os relatórios no horário de RELATORIO_CURADORIA_CRON e RELATORIO_GESTOR_CRON ("-" desliga).
 * Só com RELATORIOS_TELEGRAM_ENABLED: com o OpenClaw, o agendamento fica nele.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = {"relatorios.enabled", "relatorios.telegram.enabled", "observability.postgres.enabled"},
        havingValue = "true")
public class RelatorioAgendador {

    private static final Logger LOG = LoggerFactory.getLogger(RelatorioAgendador.class);

    private final RelatorioUseCase relatorios;

    public RelatorioAgendador(RelatorioUseCase relatorios) {
        this.relatorios = relatorios;
    }

    @Scheduled(cron = "${relatorios.curadoria.cron:0 0 8 * * *}", zone = "America/Sao_Paulo")
    public void curadoria() {
        enviar(Relatorio.Tipo.CURADORIA);
    }

    @Scheduled(cron = "${relatorios.gestor.cron:0 0 8 * * MON}", zone = "America/Sao_Paulo")
    public void gestor() {
        enviar(Relatorio.Tipo.GESTOR);
    }

    private void enviar(Relatorio.Tipo tipo) {
        try {
            relatorios.enviar(tipo);
            LOG.info("relatorio tipo={} status=enviado", tipo);
        } catch (RuntimeException ex) {
            LOG.warn("relatorio tipo={} status=falha motivo={}", tipo, ex.getMessage());
        }
    }
}
