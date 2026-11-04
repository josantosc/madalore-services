CREATE SCHEMA IF NOT EXISTS observability;

CREATE TABLE IF NOT EXISTS observability.flow_event (
    id BIGSERIAL PRIMARY KEY,
    correlation_id VARCHAR(100),
    conversation_id VARCHAR(100),
    component VARCHAR(40) NOT NULL,
    stage VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    intent VARCHAR(160),
    action VARCHAR(100),
    event_name VARCHAR(160),
    message_sid VARCHAR(100),
    delivery_status VARCHAR(40),
    business_result VARCHAR(100),
    http_status INTEGER,
    error_code VARCHAR(100),
    error_type VARCHAR(200),
    error_message VARCHAR(1000),
    duration_ms BIGINT,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_flow_event_created_at
    ON observability.flow_event (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_flow_event_correlation
    ON observability.flow_event (correlation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_flow_event_conversation
    ON observability.flow_event (conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_flow_event_component_status
    ON observability.flow_event (component, status, created_at);
CREATE INDEX IF NOT EXISTS idx_flow_event_message_sid
    ON observability.flow_event (message_sid)
    WHERE message_sid IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_flow_event_intent_action
    ON observability.flow_event (intent, action, created_at);
