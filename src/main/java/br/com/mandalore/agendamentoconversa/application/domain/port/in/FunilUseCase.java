package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;

public interface FunilUseCase {

    /** Orçamento por foto que foi para o Faturamento. {@code valor} e {@code qtdItens} nulos quando a leitura falhou. */
    void abrirOrcamento(String conta, String conversa, BigDecimal valor, Integer qtdItens);

    void registrar(Funil.Evento evento);

    record Painel(LocalDate hoje, List<Funil.Oportunidade> oportunidades) {
    }

    /** Abertas e fechadas nos últimos 30 dias. {@code contas} vazio: todas. */
    Painel painel(Set<String> contas);

    Funil.Oportunidade buscar(long id);

    void mudarEtapa(long id, Funil.Etapa etapa, Funil.MotivoPerda motivo, String usuario);

    void adiarRetorno(long id, LocalDate data, String usuario);

    /** Rotina horária: fecha as agendadas pelo assistente e as sem resposta. */
    void fecharAutomaticas();

    /** Rotina da manhã: reabre no Chatwoot as conversas com retorno no dia. Retorna quantas avisou. */
    int avisarRetornos();
}
