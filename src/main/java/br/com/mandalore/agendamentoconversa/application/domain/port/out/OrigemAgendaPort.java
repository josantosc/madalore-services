package br.com.mandalore.agendamentoconversa.application.domain.port.out;

/** Qual agenda a conta usa (mandalore.clientes.<id>.agenda, ou agenda.origem quando a conta não define). */
public interface OrigemAgendaPort {

    /** true quando a agenda da conta é a do Mandalore. */
    boolean mandalore(String conta);
}
