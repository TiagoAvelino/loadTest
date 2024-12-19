package org.acme.mqtt;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
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
    public Response sendMessage(MqttSendMessage mqttMes, @QueryParam("topic") String topic) {
        System.out.println("Entrando no metodo de envio mqtt");
        mqttClientService.publishMessage(topic, mqttMes);
        System.out.println("TOPICO: " + topic);

        return Response.ok().build();
    }

}