package br.com.mandalore.agendamentoconversa.application.domain.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.OrigemAgendaPort;

/**
 * Habilitação do atendimento clínico por conta: exige {@code atendimento.enabled=true} e que a conta use a agenda
 * do Mandalore ({@code mandalore.clientes.<id>.agenda=mandalore}). Sem a agenda ligada, nenhuma conta é habilitada.
 */
@Component
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class HabilitacaoAtendimento {

    private final OrigemAgendaPort origem;
    private final boolean enabled;

    @Autowired
    public HabilitacaoAtendimento(ObjectProvider<OrigemAgendaPort> origem,
                                  @Value("${atendimento.enabled:false}") boolean enabled) {
        this(origem.getIfAvailable(), enabled);
    }

    HabilitacaoAtendimento(OrigemAgendaPort origem, boolean enabled) {
        this.origem = origem;
        this.enabled = enabled;
    }

    public boolean habilitada(String conta) {
        return enabled && origem != null && conta != null && origem.mandalore(conta);
    }

    /** @throws AtendimentoDesabilitadoException quando a conta não está habilitada (HTTP 404) */
    public void exigir(String conta) {
        if (!habilitada(conta)) {
            throw new AtendimentoDesabilitadoException();
        }
    }
}
