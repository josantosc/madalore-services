package br.com.mandalore.agendamentoconversa.adapter.out.observability;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowReference;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.FlowEventStore;

@Component
@ConditionalOnProperty(name = "observability.postgres.enabled", havingValue = "true")
public class PostgresFlowEventStore implements FlowEventStore {

    private static final String INSERT = """
            INSERT INTO observability.flow_event (
                correlation_id, conversation_id, component, stage, status,
                intent, action, event_name, message_sid, delivery_status,
                business_result, http_status, error_code, error_type,
                error_message, duration_ms, details
            ) VALUES (
                :correlationId, :conversationId, :component, :stage, :status,
                :intent, :action, :eventName, :messageSid, :deliveryStatus,
                :businessResult, :httpStatus, :errorCode, :errorType,
                :errorMessage, :durationMs, CAST(:details AS jsonb)
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresFlowEventStore(
            @Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(FlowEvent event) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("correlationId", event.correlationId())
                .addValue("conversationId", event.conversationId())
                .addValue("component", event.component())
                .addValue("stage", event.stage())
                .addValue("status", event.status())
                .addValue("intent", event.intent())
                .addValue("action", event.action())
                .addValue("eventName", event.eventName())
                .addValue("messageSid", event.messageSid())
                .addValue("deliveryStatus", event.deliveryStatus())
                .addValue("businessResult", event.businessResult())
                .addValue("httpStatus", event.httpStatus())
                .addValue("errorCode", event.errorCode())
                .addValue("errorType", event.errorType())
                .addValue("errorMessage", event.errorMessage())
                .addValue("durationMs", event.durationMs())
                .addValue("details", json(event.details()));
        jdbc.update(INSERT, parameters);
    }

    @Override
    public Optional<FlowReference> findByMessageSid(String messageSid) {
        List<FlowReference> references = jdbc.query(
                """
                SELECT correlation_id, conversation_id
                FROM observability.flow_event
                WHERE message_sid = :messageSid
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                Map.of("messageSid", messageSid),
                (rs, row) -> new FlowReference(
                        rs.getString("correlation_id"),
                        rs.getString("conversation_id")
                )
        );
        return references.stream().findFirst();
    }

    private String json(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Detalhes de observabilidade inválidos", ex);
        }
    }
}
