package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;

/** Texto da pendência com tudo o que a recepção precisa para lançar no sistema da clínica sem abrir outra tela. */
public final class TextoPendencia {

    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("EEE dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR"));
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private TextoPendencia() {
    }

    /** anterior: o agendamento que está no sistema da clínica (remarcação ou cancelamento de remarcação ainda não lançada). */
    public static String resumo(Agenda.TipoPendencia tipo, Agenda.Agendamento agendamento, Agenda.Agendamento anterior, String observacao) {
        StringBuilder texto = new StringBuilder(tipo.titulo());
        Agenda.Paciente paciente = agendamento.paciente();
        linha(texto, "Paciente", paciente.nome());
        linha(texto, "CPF", cpf(paciente.cpf()));
        linha(texto, "Nascimento", paciente.nascimento() == null ? "" : DATA.format(paciente.nascimento()));
        linha(texto, "Telefone", paciente.telefone());
        linha(texto, "Profissional", agendamento.profissional());
        linha(texto, "Especialidade", agendamento.especialidade());
        linha(texto, "Convênio", agendamento.convenio() == null ? "não informado" : agendamento.convenio());
        if (tipo == Agenda.TipoPendencia.REMARCAR && anterior != null) {
            linha(texto, "De", dataHora(anterior.inicio()));
            linha(texto, "Código no sistema da clínica", anterior.codigoExterno());
            linha(texto, "Para", dataHora(agendamento.inicio()));
        } else if (anterior != null) {
            linha(texto, "Data", dataHora(anterior.inicio()));
            linha(texto, "Código no sistema da clínica", anterior.codigoExterno());
        } else {
            linha(texto, "Data", dataHora(agendamento.inicio()));
            linha(texto, "Código no sistema da clínica", agendamento.codigoExterno());
        }
        linha(texto, "Local", agendamento.local());
        linha(texto, "Origem", agendamento.origem() == Agenda.Origem.BOT ? "bot do WhatsApp" : "recepção");
        linha(texto, "Observação", observacao);
        return texto.toString();
    }

    public static String dataHora(LocalDateTime quando) {
        return quando == null ? "" : DATA_HORA.format(quando);
    }

    static String cpf(String digitos) {
        if (digitos == null || digitos.length() != 11) {
            return digitos;
        }
        return digitos.substring(0, 3) + "." + digitos.substring(3, 6) + "." + digitos.substring(6, 9) + "-" + digitos.substring(9);
    }

    private static void linha(StringBuilder texto, String rotulo, String valor) {
        if (valor != null && !valor.isBlank()) {
            texto.append('\n').append(rotulo).append(": ").append(valor.trim());
        }
    }
}
