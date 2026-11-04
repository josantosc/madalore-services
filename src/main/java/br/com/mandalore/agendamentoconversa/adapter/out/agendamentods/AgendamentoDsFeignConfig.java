package br.com.mandalore.agendamentoconversa.adapter.out.agendamentods;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import feign.Request;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;

public class AgendamentoDsFeignConfig {

    @Bean
    public Request.Options agendamentoDsOptions(
            @Value("${agendamento-ds.connect-timeout-ms:3000}") long connect,
            @Value("${agendamento-ds.read-timeout-ms:7000}") long read
    ) {
        return new Request.Options(connect, TimeUnit.MILLISECONDS, read, TimeUnit.MILLISECONDS, true);
    }

    @Bean
    public ErrorDecoder agendamentoDsErrorDecoder(ObjectMapper mapper) {
        return (methodKey, response) -> new AgendamentoDsException(response.status(), codigo(mapper, response));
    }

    private String codigo(ObjectMapper mapper, Response response) {
        if (response.body() == null) {
            return "";
        }
        try {
            String body = Util.toString(response.body().asReader(StandardCharsets.UTF_8));
            JsonNode node = mapper.readTree(body);
            return node.path("code").asText("");
        } catch (Exception ex) {
            return "";
        }
    }
}
