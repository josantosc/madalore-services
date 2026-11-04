package br.com.mandalore.agendamentoconversa.adapter.out.agendamentods;

public class AgendamentoDsException extends RuntimeException {

    private final int status;
    private final String codigo;

    public AgendamentoDsException(int status, String codigo) {
        super(codigo == null || codigo.isBlank() ? "agendamento-ds " + status : codigo);
        this.status = status;
        this.codigo = codigo == null ? "" : codigo;
    }

    public int getStatus() {
        return status;
    }

    public String getCodigo() {
        return codigo;
    }
}
