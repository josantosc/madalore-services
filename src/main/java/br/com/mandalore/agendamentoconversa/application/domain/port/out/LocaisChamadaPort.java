package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;

/** Locais sugeridos para chamar o paciente na clínica (Consultório 1, Recepção, Financeiro...), em ordem. */
public interface LocaisChamadaPort {

    LocaisChamadaPort NENHUM = conta -> List.of();

    List<String> locais(String conta);
}
