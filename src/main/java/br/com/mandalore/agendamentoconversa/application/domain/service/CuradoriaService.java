package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.CuradoriaUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.CuradoriaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.TreinamentoPort;

@Service
@ConditionalOnProperty(name = {"curadoria.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class CuradoriaService implements CuradoriaUseCase {

    /** Limite do Dialogflow ES para uma frase de treino. */
    static final int LIMITE_FRASE = 768;

    static final Duration VALIDADE = Duration.ofMinutes(5);

    private final CuradoriaPort curadoria;
    private final TreinamentoPort treinamento;
    private final double limiteConfianca;
    private volatile List<Curadoria.IntentAgente> cache;
    private volatile Instant lidasEm = Instant.MIN;

    public CuradoriaService(
            CuradoriaPort curadoria,
            TreinamentoPort treinamento,
            @Value("${curadoria.limite-confianca:0.6}") double limiteConfianca
    ) {
        this.curadoria = curadoria;
        this.treinamento = treinamento;
        this.limiteConfianca = limiteConfianca;
    }

    @Override
    public Curadoria.Indicadores indicadores(int dias) {
        return curadoria.indicadores(periodo(dias), limiteConfianca);
    }

    @Override
    public List<Curadoria.Frase> fila(int dias) {
        return curadoria.fila(periodo(dias), limiteConfianca);
    }

    @Override
    public List<Curadoria.IntentInfo> intents() {
        return GuiaIntents.catalogo(agente());
    }

    @Override
    public List<Curadoria.Sugestao> sugestoes(String fala) {
        return GuiaIntents.sugestoes(fala, agente());
    }

    /** As intents mudam só quando alguém publica o agente ou ensina frase; a lista fica em memória por um tempo. */
    private List<Curadoria.IntentAgente> agente() {
        List<Curadoria.IntentAgente> atuais = cache;
        if (atuais == null || lidasEm.plus(VALIDADE).isBefore(Instant.now())) {
            atuais = List.copyOf(treinamento.intents());
            cache = atuais;
            lidasEm = Instant.now();
        }
        return atuais;
    }

    @Override
    public boolean decidir(Curadoria.Decisao decisao) {
        if (decisao == null || decisao.acao() == null || vazio(decisao.texto())) {
            throw new IllegalArgumentException("texto e ação são obrigatórios");
        }
        boolean nova = true;
        if (decisao.acao() == Curadoria.Acao.ENSINAR) {
            if (vazio(decisao.intent())) {
                throw new IllegalArgumentException("escolha a intent");
            }
            String frase = decisao.frase() == null ? "" : decisao.frase().trim();
            if (frase.isEmpty() || frase.length() > LIMITE_FRASE) {
                throw new IllegalArgumentException("frase vazia ou acima de " + LIMITE_FRASE + " caracteres");
            }
            if (frase.matches(".*<(cpf|data|telefone|email|numero)>.*")) {
                throw new IllegalArgumentException("troque os marcadores <...> por um exemplo fictício antes de ensinar");
            }
            String nome = decisao.intent();
            agente().stream().filter(i -> i.nome().equals(nome)).findFirst().ifPresent(i -> {
                if (!i.ensinavel()) {
                    throw new IllegalArgumentException(nome + " é disparada pelo sistema ou é o \"não entendi\"; "
                            + "frase de treino nela não muda nada. Escolha outra intent.");
                }
            });
            nova = treinamento.ensinar(nome, frase);
            cache = null;
            decisao = new Curadoria.Decisao(decisao.texto(), decisao.acao(), decisao.intent(), frase, decisao.usuario());
        }
        curadoria.registrar(decisao);
        return nova;
    }

    private static int periodo(int dias) {
        return Math.max(1, Math.min(dias, 90));
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}
