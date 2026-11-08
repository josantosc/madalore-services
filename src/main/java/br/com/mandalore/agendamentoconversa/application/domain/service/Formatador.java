package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.util.List;

import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;

public final class Formatador {

    private Formatador() {
    }

    public static String opcoes(List<ItemCatalogo> itens) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < itens.size(); i++) {
            if (i > 0) {
                texto.append('\n');
            }
            texto.append(i + 1).append(". ").append(itens.get(i).nome());
        }
        return texto.toString();
    }

    public static String horarios(String especialidade, String periodo, List<HorarioOferta> horarios) {
        return horarios(null, especialidade, periodo, horarios);
    }

    public static String horarios(String aviso, String especialidade, String periodo, List<HorarioOferta> horarios) {
        StringBuilder texto = new StringBuilder();
        if (aviso != null && !aviso.isBlank()) {
            texto.append(aviso).append("\n\n");
        }
        texto.append("Encontrei estes horários de ")
                .append(especialidade == null || especialidade.isBlank() ? "consulta" : especialidade)
                .append(", ")
                .append(Textos.periodoFrase(periodo))
                .append(":\n\n");
        for (int i = 0; i < horarios.size(); i++) {
            texto.append(linhaHorario(i + 1, horarios.get(i))).append('\n');
        }
        texto.append("\nResponda com o número do horário. Para voltar, digite voltar.");
        return texto.toString().trim();
    }

    public static String agendamentos(List<AgendamentoAberto> itens) {
        return agendamentos(null, itens);
    }

    public static String agendamentos(String aviso, List<AgendamentoAberto> itens) {
        StringBuilder texto = new StringBuilder();
        if (aviso != null && !aviso.isBlank()) {
            texto.append(aviso).append("\n\n");
        }
        texto.append("Você tem estes agendamentos:\n\n");
        for (int i = 0; i < itens.size(); i++) {
            AgendamentoAberto item = itens.get(i);
            texto.append(i + 1).append(". ").append(Textos.dia(item.dataHora()));
            if (item.especialidade() != null && !item.especialidade().isBlank()) {
                texto.append(", ").append(item.especialidade());
            }
            if (item.dataHora() != null) {
                texto.append(", ").append(Textos.hora(item.dataHora()));
            }
            if (item.local() != null && !item.local().isBlank()) {
                texto.append(", ").append(item.local());
            }
            texto.append('\n');
        }
        texto.append("\nPara continuar, digite confirmar, remarcar ou cancelar e o número. Por exemplo: remarcar 1.");
        return texto.toString().trim();
    }

    public static String acoesAgendamento(int numero, AgendamentoAberto item) {
        StringBuilder texto = new StringBuilder("O que você quer fazer com a consulta");
        if (item.especialidade() != null && !item.especialidade().isBlank()) {
            texto.append(" de ").append(item.especialidade());
        }
        if (item.dataHora() != null) {
            texto.append(" do dia ").append(Textos.dia(item.dataHora())).append(", às ").append(Textos.hora(item.dataHora()));
        }
        return texto.append("?\n\nDigite confirmar ").append(numero)
                .append(", remarcar ").append(numero)
                .append(" ou cancelar ").append(numero).append('.')
                .toString();
    }

    public static String resumoMarcacao(String especialidade, HorarioOferta horario, String convenio) {
        return resumoAgendamento(
                especialidade == null || especialidade.isBlank() ? horario.especialidade() : especialidade,
                Textos.diaResumo(horario.dataHora()),
                Textos.hora(horario.dataHora()),
                horario.medico(),
                convenio
        );
    }

    public static String textoMarcado(
            String nome,
            String especialidade,
            String data,
            String hora,
            String medico,
            String convenio
    ) {
        String titulo = nome == null || nome.isBlank()
                ? "Agendamento confirmado!"
                : "Agendamento confirmado, " + Textos.primeiroNome(nome) + "!";
        return titulo + "\n\n"
                + resumoAgendamento(especialidade, data, hora, medico, convenio)
                + "\n\nPosso ajudar em mais alguma coisa?"
                + "\n1. Marcar outra consulta"
                + "\n2. Encerrar atendimento";
    }

    public static String resumoExame(String exame, HorarioOferta horario, String convenio) {
        return resumoExame(exame, Textos.diaResumo(horario.dataHora()), Textos.hora(horario.dataHora()), horario.local(), convenio);
    }

    public static String textoExameMarcado(
            String nome,
            String exame,
            String data,
            String hora,
            String local,
            String convenio
    ) {
        String titulo = nome == null || nome.isBlank()
                ? "Exame agendado!"
                : "Exame agendado, " + Textos.primeiroNome(nome) + "!";
        return titulo + "\n\n" + resumoExame(exame, data, hora, local, convenio);
    }

    public static String orientacoesExame(String exame, String preparo, String gerais) {
        StringBuilder texto = new StringBuilder();
        if (preparo != null && !preparo.isBlank()) {
            texto.append("Orientações para ").append(vazio(exame)).append(":\n\n").append(preparo.trim());
        }
        if (gerais != null && !gerais.isBlank()) {
            if (texto.length() > 0) {
                texto.append("\n\n");
            }
            texto.append(gerais.trim());
        }
        return texto.toString();
    }

    public static String fechamentoExame() {
        return "Posso ajudar em mais alguma coisa?"
                + "\n1. Marcar outro exame"
                + "\n2. Encerrar atendimento";
    }

    public static String convenios(String aviso, List<ItemCatalogo> itens) {
        return aviso + "\n\nQual convênio você gostaria de usar?\n\n" + opcoes(itens)
                + "\n\nResponda com o número ou o nome da opção. Para voltar, digite voltar.";
    }

    public static String textoCancelado(String data, String hora) {
        return "Cancelei a consulta de " + data + ", " + hora + ".";
    }

    private static String linhaHorario(int indice, HorarioOferta horario) {
        StringBuilder texto = new StringBuilder();
        texto.append(indice).append(". ").append(Textos.diaSemanaCurto(horario.dataHora())).append(", ").append(Textos.hora(horario.dataHora()));
        if (horario.medico() != null && !horario.medico().isBlank()) {
            texto.append(", com ").append(horario.medico());
        } else if (horario.local() != null && !horario.local().isBlank()) {
            texto.append(", ").append(horario.local());
        }
        return texto.toString();
    }

    private static String resumoExame(String exame, String data, String hora, String local, String convenio) {
        StringBuilder texto = new StringBuilder();
        texto.append("Exame: ").append(vazio(exame));
        if (local != null && !local.isBlank()) {
            texto.append("\nLocal: ").append(local);
        }
        texto.append("\nData: ").append(vazio(data));
        texto.append("\nHorário: ").append(vazio(hora));
        if (convenio != null && !convenio.isBlank()) {
            texto.append("\nConvênio: ").append(convenio);
        }
        return texto.toString();
    }

    private static String vazio(String valor) {
        return valor == null ? "" : valor;
    }

    private static String resumoAgendamento(
            String especialidade,
            String data,
            String hora,
            String medico,
            String convenio
    ) {
        StringBuilder texto = new StringBuilder();
        if (medico != null && !medico.isBlank()) {
            texto.append("Consulta com: ").append(medico).append('\n');
        }
        texto.append("Especialidade: ").append(vazio(especialidade));
        texto.append("\nData: ").append(vazio(data));
        texto.append("\nHorário: ").append(vazio(hora));
        if (convenio != null && !convenio.isBlank()) {
            texto.append("\nConvênio: ").append(convenio);
        }
        return texto.toString();
    }
}
