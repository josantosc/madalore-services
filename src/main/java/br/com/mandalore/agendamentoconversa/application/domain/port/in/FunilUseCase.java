package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;

public interface FunilUseCase {

    /**
     * Orçamento automático (foto do pedido ou exames escritos pelo paciente) que foi para o Faturamento.
     * {@code valor} e {@code qtdItens} nulos quando a leitura falhou.
     */
    void abrirOrcamento(String conta, String conversa, Funil.Origem origem, BigDecimal valor, Integer qtdItens);

    /** Comando /orcamento do atendente: abre (ou usa a aberta) e grava total e quantidade de exames. */
    void orcamentoCalculado(String conta, String conversa, BigDecimal valor, int qtdItens, String atendente);

    void registrar(Funil.Evento evento);

    record Painel(LocalDate hoje, List<Funil.Oportunidade> oportunidades) {
    }

    /** Abertas e fechadas nos últimos 30 dias. {@code contas} vazio: todas. */
    Painel painel(Set<String> contas);

    Funil.Oportunidade buscar(long id);

    void mudarEtapa(long id, Funil.Etapa etapa, Funil.MotivoPerda motivo, String usuario);

    void adiarRetorno(long id, LocalDate data, String usuario);

    /** Valor informado pelo atendente (orçamento passado por texto ou telefone, ou correção da leitura da foto). */
    void definirValor(long id, BigDecimal valor, String usuario);

    /** Rotina horária: fecha as agendadas pelo assistente e as sem resposta. */
    void fecharAutomaticas();

    /** Rotina da manhã: reabre no Chatwoot as conversas com retorno no dia. Retorna quantas avisou. */
    int avisarRetornos();
}
