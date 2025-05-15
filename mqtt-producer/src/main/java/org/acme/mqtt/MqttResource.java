package org.acme.mqtt;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/mqtt")
public class MqttResource {

    @Inject
    MqttClientService mqttClientService;

    @POST
    @Path("/send")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendMessage(MqttSendMessage mqttMes, @QueryParam("topic") String topic) {
        if (topic == null || topic.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Topic cannot be null or empty"))
                    .build();
        }

        try {
            mqttClientService.publishMessage(topic, mqttMes);
            return Response.ok(Map.of("status", "Message sent successfully", "topic", topic)).build();
        } catch (Exception e) {
            // Ideally, use a logger here instead of System.out
            System.err.println("Failed to publish message: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to send message", "details", e.getMessage()))
                    .build();
        }
    }
}
