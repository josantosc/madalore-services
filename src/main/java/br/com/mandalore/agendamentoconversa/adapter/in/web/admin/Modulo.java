package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/** Telas da central de administração. O caminho define qual permissão a requisição exige. */
public enum Modulo {

    CURADORIA("/curadoria", "Curadoria do agente",
            "Falas que o assistente não entendeu, intents mais usadas e ensino de frases novas."),
    PRECOS("/precos", "Tabela de preços",
            "Valores particulares usados no orçamento por foto do pedido médico."),
    SIMULADOR("/simulador", "Simulador de conversa",
            "Converse com o assistente como paciente, direto no Dialogflow ou pelo Chatwoot."),
    AGENDA("/agenda", "Agenda da clínica",
            "Consultas do bot e da recepção, pendências para lançar no sistema da clínica e conferência diária."),
    AGENDA_CADASTROS("/agenda/cadastros", "Cadastros da agenda",
            "Profissionais, especialidades, convênios e horários de atendimento que o bot oferece."),
    FUNIL("/funil", "Funil de orçamentos",
            "Orçamentos que ainda não viraram agendamento, retornos do dia e motivos de perda."),
    USUARIOS("/usuarios", "Usuários",
            "Quem entra na central, com qual perfil e em quais clínicas. Senhas provisórias e histórico de acesso.");

    private final String caminho;
    private final String titulo;
    private final String descricao;

    Modulo(String caminho, String titulo, String descricao) {
        this.caminho = caminho;
        this.titulo = titulo;
        this.descricao = descricao;
    }

    public String caminho() {
        return caminho;
    }

    public String titulo() {
        return titulo;
    }

    public String descricao() {
        return descricao;
    }

    /** O caminho mais específico vence: /agenda/cadastros/... é AGENDA_CADASTROS, não AGENDA. */
    public static Optional<Modulo> doCaminho(String caminho) {
        return Arrays.stream(values())
                .filter(m -> caminho.equals(m.caminho) || caminho.startsWith(m.caminho + "/"))
                .max(Comparator.comparingInt(m -> m.caminho.length()));
    }
}
