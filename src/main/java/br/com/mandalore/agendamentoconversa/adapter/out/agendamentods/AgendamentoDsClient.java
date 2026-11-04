package br.com.mandalore.agendamentoconversa.adapter.out.agendamentods;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "agendamento-ds",
        url = "${agendamento-ds.base-url}",
        configuration = AgendamentoDsFeignConfig.class
)
public interface AgendamentoDsClient {

    @PostMapping("/agendamento/integrar-agendamento")
    IntegrarResponse integrar(@RequestBody IntegrarRequest request);

    record IntegrarRequest(
            String tipoAgendamento,
            BigDecimal cdAgenda,
            LocalDateTime dataAgenda,
            PacienteRequest paciente
    ) {
    }

    record PacienteRequest(String cpf, String nome, LocalDate dataNascimento) {
    }

    record IntegrarResponse(
            String tipoAgendamento,
            BigDecimal nrAgendamento,
            String nmPaciente,
            BigDecimal cdAgenda,
            LocalDateTime dataAgenda
    ) {
    }
}
