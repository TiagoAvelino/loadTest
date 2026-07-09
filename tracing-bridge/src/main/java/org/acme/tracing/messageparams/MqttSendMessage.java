package org.acme.tracing.messageparams;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Single-object payload with explicit fields for each attribute you need.
 * All new fields are Strings to mirror the incoming/outgoing JSON exactly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MqttSendMessage {

    /**
     * Nested class representing a field with name and value
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Field {
        private String name;
        private String value;

        public Field() {
        }

        public Field(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    // --- Your original attributes (optional) ---
    private String jwt;
    private String message;
    private String host;
    private Boolean isThereAny;

    // Force lowercase on the wire so OTel can extract without guesswork
    @JsonProperty("traceparent")
    private String traceParent;

    @JsonProperty("tracestate")
    private String traceState;

    // --- Fields array for dynamic name/value pairs ---
    private List<Field> fields;

    // --- Constructors ---
    public MqttSendMessage() {
    }

    // --- Getters/Setters: original attributes ---
    public String getJwt() {
        return jwt;
    }

    public void setJwt(String jwt) {
        this.jwt = jwt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Boolean getIsThereAny() {
        return isThereAny;
    }

    public void setIsThereAny(Boolean isThereAny) {
        this.isThereAny = isThereAny;
    }

    public String getTraceParent() {
        return traceParent;
    }

    public void setTraceParent(String traceParent) {
        this.traceParent = traceParent;
    }

    public String getTraceState() {
        return traceState;
    }

    public void setTraceState(String traceState) {
        this.traceState = traceState;
    }

    // --- Getter/Setter for fields array ---
    public List<Field> getFields() {
        return fields;
    }

    public void setFields(List<Field> fields) {
        this.fields = fields;
    }
}
