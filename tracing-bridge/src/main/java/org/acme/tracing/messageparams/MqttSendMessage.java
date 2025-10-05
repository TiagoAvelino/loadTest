package org.acme.tracing.messageparams;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MqttSendMessage {
    private String jwt;
    private String message;
    private String host;
    private Boolean isThereAny;

    // Force lowercase on the wire so OTel can extract without guesswork
    @JsonProperty("traceparent")
    private String traceParent;

    @JsonProperty("tracestate")
    private String traceState;

    public MqttSendMessage() {
    }

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

    // Keep camelCase getters/setters for your app, but JSON field name is forced by
    // @JsonProperty
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
}
