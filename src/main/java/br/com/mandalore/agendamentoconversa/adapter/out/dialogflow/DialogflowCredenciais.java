package br.com.mandalore.agendamentoconversa.adapter.out.dialogflow;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.auth.oauth2.GoogleCredentials;

/** Token OAuth da conta de serviço; DIALOGFLOW_CREDENTIALS aceita o JSON inline ou o caminho do arquivo. */
@Component
class DialogflowCredenciais {

    private static final String ESCOPO = "https://www.googleapis.com/auth/dialogflow";

    private final String credenciais;

    DialogflowCredenciais(@Value("${dialogflow.credentials:}") String credenciais) {
        this.credenciais = credenciais;
    }

    String token() {
        try (InputStream stream = abrir()) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(stream).createScoped(ESCOPO);
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) {
                credentials.refresh();
            }
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao autenticar no Dialogflow", ex);
        }
    }

    static String host(String regiao) {
        return regiao == null || regiao.isBlank()
                ? "https://dialogflow.googleapis.com"
                : "https://" + regiao.trim() + "-dialogflow.googleapis.com";
    }

    private InputStream abrir() throws Exception {
        if (credenciais == null || credenciais.isBlank()) {
            throw new IllegalStateException("DIALOGFLOW_CREDENTIALS ausente");
        }
        String valor = credenciais.trim();
        if (valor.startsWith("{")) {
            return new ByteArrayInputStream(valor.getBytes(StandardCharsets.UTF_8));
        }
        return new FileInputStream(valor);
    }
}
