package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class OrientacoesExame {

    private static final String ARQUIVO = "/orientacoes/exame-geral.txt";
    private static final String GERAIS = carregar();

    private OrientacoesExame() {
    }

    static String gerais() {
        return GERAIS;
    }

    private static String carregar() {
        try (InputStream entrada = OrientacoesExame.class.getResourceAsStream(ARQUIVO)) {
            return entrada == null ? "" : new String(entrada.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
