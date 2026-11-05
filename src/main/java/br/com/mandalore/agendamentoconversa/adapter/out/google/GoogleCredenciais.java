package br.com.mandalore.agendamentoconversa.adapter.out.google;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.auth.oauth2.GoogleCredentials;

/** Conta de serviço do Google (DIALOGFLOW_CREDENTIALS): JSON inline ou caminho do arquivo. */
@Component
public class GoogleCredenciais {

    public static final String DIALOGFLOW = "https://www.googleapis.com/auth/dialogflow";
    public static final String CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";

    private final String credenciais;
    private final Map<String, GoogleCredentials> porEscopo = new ConcurrentHashMap<>();

    public GoogleCredenciais(@Value("${dialogflow.credentials:}") String credenciais) {
        this.credenciais = credenciais;
    }

    public String token(String escopo) {
        try {
            GoogleCredentials credentials = porEscopo.computeIfAbsent(escopo, this::carregar);
            synchronized (credentials) {
                credentials.refreshIfExpired();
                return credentials.getAccessToken().getTokenValue();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao autenticar no Google", ex);
        }
    }

    private GoogleCredentials carregar(String escopo) {
        try (InputStream stream = abrir()) {
            return GoogleCredentials.fromStream(stream).createScoped(escopo);
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao ler as credenciais do Google", ex);
        }
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
