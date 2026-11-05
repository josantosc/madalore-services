package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import java.util.Arrays;
import java.util.Optional;

/** Telas da central de administração. O caminho define qual permissão a requisição exige. */
public enum Modulo {

    CURADORIA("/curadoria", "Curadoria do agente",
            "Falas que o assistente não entendeu, intents mais usadas e ensino de frases novas."),
    PRECOS("/precos", "Tabela de preços",
            "Valores particulares usados no orçamento por foto do pedido médico."),
    SIMULADOR("/simulador", "Simulador de conversa",
            "Converse com o assistente como paciente, direto no Dialogflow ou pelo Chatwoot.");

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

    public static Optional<Modulo> doCaminho(String caminho) {
        return Arrays.stream(values())
                .filter(m -> caminho.equals(m.caminho) || caminho.startsWith(m.caminho + "/"))
                .findFirst();
    }
}
