package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.util.Map;

public record ResultadoOperacao(
        String evento,
        Map<String, String> parametros,
        ContextoConversa contexto,
        CodigoNegocio codigo
) {
}
