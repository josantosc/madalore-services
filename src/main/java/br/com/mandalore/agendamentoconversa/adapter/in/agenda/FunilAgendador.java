package br.com.mandalore.agendamentoconversa.adapter.in.agenda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;

/** Fecha as oportunidades agendadas ou sem resposta (FUNIL_FECHAMENTO_CRON) e avisa os retornos do dia (FUNIL_RETORNO_CRON). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = {"funil.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class FunilAgendador {

    private static final Logger LOG = LoggerFactory.getLogger(FunilAgendador.class);

    private final FunilUseCase funil;

    public FunilAgendador(FunilUseCase funil) {
        this.funil = funil;
    }

    @Scheduled(cron = "${funil.fechamento.cron:0 10 * * * *}", zone = "America/Sao_Paulo")
    public void fechar() {
        try {
            funil.fecharAutomaticas();
        } catch (RuntimeException ex) {
            LOG.warn("funil fechamento status=falha motivo={}", ex.getMessage());
        }
    }

    @Scheduled(cron = "${funil.retorno.cron:0 0 8 * * MON-SAT}", zone = "America/Sao_Paulo")
    public void retornos() {
        try {
            funil.fecharAutomaticas();
            funil.avisarRetornos();
        } catch (RuntimeException ex) {
            LOG.warn("funil retornos status=falha motivo={}", ex.getMessage());
        }
    }
}
