package br.com.mandalore.agendamentoconversa.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;

@SpringBootTest
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresFlowEventStoreIT {

    @Autowired
    private PostgresFlowEventStore store;

    @Autowired
    @Qualifier("observabilityJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void gravaEventoERecuperaCorrelacaoPeloMessageSid() {
        String sid = "SM-OBSERVABILITY-TEST";
        jdbc.update(
                "DELETE FROM observability.flow_event WHERE message_sid = :sid",
                Map.of("sid", sid)
        );

        store.save(FlowEvent.builder("TWILIO", "delivery.callback", "ERROR")
                .correlation("corr-1", "conv-1")
                .intent("acao.agenda.agendar", "agendar")
                .message(sid, "failed")
                .error("30003", "TwilioDeliveryException", "Unreachable destination handset")
                .detail("safe", true)
                .build());

        assertThat(store.findByMessageSid(sid))
                .hasValueSatisfying(reference -> {
                    assertThat(reference.correlationId()).isEqualTo("corr-1");
                    assertThat(reference.conversationId()).isEqualTo("conv-1");
                });
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM observability.flow_event
                WHERE message_sid = :sid
                  AND details ->> 'safe' = 'true'
                """,
                Map.of("sid", sid),
                Integer.class
        );
        assertThat(count).isEqualTo(1);
        jdbc.update(
                "DELETE FROM observability.flow_event WHERE message_sid = :sid",
                Map.of("sid", sid)
        );
    }
}
