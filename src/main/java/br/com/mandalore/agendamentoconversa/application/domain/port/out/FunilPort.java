package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;

public interface FunilPort {

    /** Abre a oportunidade da conversa ou, se já houver uma aberta, atualiza valor e quantidade. Retorna o id. */
    long abrir(String conta, String conversa, Funil.Origem origem, BigDecimal valor, Integer qtdItens,
               LocalDate proximaAcao, String usuario);

    Optional<Funil.Oportunidade> aberta(String conta, String conversa);

    Optional<Funil.Oportunidade> buscar(long id);

    /**
     * Muda a etapa só se ela ainda for {@code de}. Etapa fechada zera a próxima ação; {@code responsavel} nulo
     * mantém o atual. Retorna false quando outra mudança chegou antes.
     */
    boolean mudar(long id, Funil.Etapa de, Funil.Etapa para, Funil.MotivoPerda motivo, LocalDate proximaAcao,
                  String responsavel, String usuario);

    void proximaAcao(long id, LocalDate data, String usuario);

    /** O paciente respondeu: zera o prazo de perda por falta de resposta. */
    void respondeu(long id);

    /**
     * Troca o valor (e a quantidade de exames, quando informada) e registra no histórico.
     * Retorna false se a oportunidade não existe.
     */
    boolean valor(long id, BigDecimal valor, Integer qtdItens, String usuario);

    /** Nome do paciente e responsável vindos do Chatwoot; nulo mantém o atual. */
    void contato(long id, String paciente, String responsavel);

    /** Abertas e as fechadas desde {@code fechadasDesde}. {@code contas} vazio: todas. */
    List<Funil.Oportunidade> listar(Set<String> contas, Instant fechadasDesde);

    /** Abertas com retorno até {@code dia} e ainda sem aviso no Chatwoot nesse dia. */
    List<Funil.Oportunidade> retornosPendentes(LocalDate dia);

    void retornoAvisado(long id, LocalDate dia);

    /** Fecha como agendada a oportunidade cuja conversa teve agendamento pelo assistente depois da abertura. */
    int fecharAgendadasPelaTrilha();

    /** Fecha como perdida a oportunidade sem resposta do paciente desde {@code limite}. */
    int fecharSemResposta(Instant limite);
}
