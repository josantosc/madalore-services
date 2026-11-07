package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.Map;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;

public interface RegrasFunilPort {

    /** Cadência inicial das contas que ainda não gravaram a própria (variáveis FUNIL_* do .env). */
    Funil.Regras padrao();

    /** Contas do Chatwoot que gravaram a cadência na tela. */
    Map<String, Funil.RegrasSalvas> salvas();

    default Funil.Regras regras(String conta) {
        Funil.RegrasSalvas daConta = conta == null ? null : salvas().get(conta);
        return daConta == null ? padrao() : daConta.regras();
    }

    /** Grava a cadência da conta e registra no histórico o que era antes. */
    void salvar(String conta, Funil.Regras regras, String usuario);
}
