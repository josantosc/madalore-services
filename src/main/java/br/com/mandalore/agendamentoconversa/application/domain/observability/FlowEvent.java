package br.com.mandalore.agendamentoconversa.application.domain.observability;

import java.util.LinkedHashMap;
import java.util.Map;

public record FlowEvent(
        String correlationId,
        String conversationId,
        String component,
        String stage,
        String status,
        String intent,
        String action,
        String eventName,
        String messageSid,
        String deliveryStatus,
        String businessResult,
        Integer httpStatus,
        String errorCode,
        String errorType,
        String errorMessage,
        Long durationMs,
        Map<String, Object> details
) {

    public FlowEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static Builder builder(String component, String stage, String status) {
        return new Builder(component, stage, status);
    }

    public static final class Builder {

        private final String component;
        private final String stage;
        private final String status;
        private String correlationId;
        private String conversationId;
        private String intent;
        private String action;
        private String eventName;
        private String messageSid;
        private String deliveryStatus;
        private String businessResult;
        private Integer httpStatus;
        private String errorCode;
        private String errorType;
        private String errorMessage;
        private Long durationMs;
        private final Map<String, Object> details = new LinkedHashMap<>();

        private Builder(String component, String stage, String status) {
            this.component = component;
            this.stage = stage;
            this.status = status;
        }

        public Builder correlation(String correlationId, String conversationId) {
            this.correlationId = correlationId;
            this.conversationId = conversationId;
            return this;
        }

        public Builder intent(String intent, String action) {
            this.intent = intent;
            this.action = action;
            return this;
        }

        public Builder eventName(String eventName) {
            this.eventName = eventName;
            return this;
        }

        public Builder message(String messageSid, String deliveryStatus) {
            this.messageSid = messageSid;
            this.deliveryStatus = deliveryStatus;
            return this;
        }

        public Builder businessResult(String businessResult) {
            this.businessResult = businessResult;
            return this;
        }

        public Builder httpStatus(Integer httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder error(String errorCode, Throwable error) {
            this.errorCode = errorCode;
            if (error != null) {
                this.errorType = error.getClass().getSimpleName();
                this.errorMessage = error.getMessage();
            }
            return this;
        }

        public Builder error(String errorCode, String errorType, String errorMessage) {
            this.errorCode = errorCode;
            this.errorType = errorType;
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder detail(String key, Object value) {
            if (key != null && value != null) {
                details.put(key, value);
            }
            return this;
        }

        public FlowEvent build() {
            return new FlowEvent(
                    correlationId,
                    conversationId,
                    component,
                    stage,
                    status,
                    intent,
                    action,
                    eventName,
                    messageSid,
                    deliveryStatus,
                    businessResult,
                    httpStatus,
                    errorCode,
                    errorType,
                    errorMessage,
                    durationMs,
                    details
            );
        }
    }
}
