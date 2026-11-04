package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.model.IdentidadePaciente;

@Component
public class IdentidadeSessao {

    private final ConcurrentHashMap<String, IdentidadePaciente> porPaciente = new ConcurrentHashMap<>();

    public void guardar(String pacienteId, IdentidadePaciente identidade) {
        if (pacienteId != null && identidade != null) {
            porPaciente.put(pacienteId, identidade);
        }
    }

    public IdentidadePaciente buscar(String pacienteId) {
        return pacienteId == null ? null : porPaciente.get(pacienteId);
    }
}
