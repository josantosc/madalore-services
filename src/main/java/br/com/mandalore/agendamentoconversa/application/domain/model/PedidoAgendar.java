package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PedidoAgendar(
        String chaveIdempotencia,
        String pacienteId,
        String cdAgenda,
        LocalDateTime dataAgenda,
        String cpf,
        LocalDate nascimento,
        String nome,
        boolean semCadastro,
        ProcedimentoExame exame,
        String convenioId
) {

    public PedidoAgendar(
            String chaveIdempotencia,
            String pacienteId,
            String cdAgenda,
            LocalDateTime dataAgenda,
            String cpf,
            LocalDate nascimento,
            String nome,
            boolean semCadastro,
            ProcedimentoExame exame
    ) {
        this(chaveIdempotencia, pacienteId, cdAgenda, dataAgenda, cpf, nascimento, nome, semCadastro, exame, null);
    }

    public PedidoAgendar(
            String chaveIdempotencia,
            String pacienteId,
            String cdAgenda,
            LocalDateTime dataAgenda,
            String cpf,
            LocalDate nascimento,
            String nome,
            boolean semCadastro
    ) {
        this(chaveIdempotencia, pacienteId, cdAgenda, dataAgenda, cpf, nascimento, nome, semCadastro, null, null);
    }

    public boolean isExame() {
        return exame != null;
    }
}
