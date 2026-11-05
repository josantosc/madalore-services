package br.com.mandalore.agendamentoconversa.adapter.out.dialogflow;

import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.out.google.GoogleCredenciais;

@Component
class DialogflowCredenciais {

    private final GoogleCredenciais google;

    DialogflowCredenciais(GoogleCredenciais google) {
        this.google = google;
    }

    String token() {
        return google.token(GoogleCredenciais.DIALOGFLOW);
    }

    static String host(String regiao) {
        return regiao == null || regiao.isBlank()
                ? "https://dialogflow.googleapis.com"
                : "https://" + regiao.trim() + "-dialogflow.googleapis.com";
    }
}
