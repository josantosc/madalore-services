package br.com.mandalore.agendamentoconversa.application.domain.exception;

/**
 * Usuário sem permissão para a ação: sem vínculo com um profissional ou atendimento de outro profissional.
 * Os controllers traduzem para HTTP 403.
 */
public class AcessoNegadoException extends RuntimeException {

    public AcessoNegadoException(String message) {
        super(message);
    }
}
