package br.com.mandalore.agendamentoconversa.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;

@SpringBootTest(properties = {"curadoria.enabled=true", "curadoria.user=u", "curadoria.password=p"})
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresCuradoriaAdapterIT {

    private static final String CONVERSA = "curadoria-it";

    @Autowired
    private PostgresFlowEventStore eventos;

    @Autowired
    private PostgresCuradoriaAdapter curadoria;

    @Autowired
    @Qualifier("observabilityJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM observability.flow_event WHERE conversation_id = :c", Map.of("c", CONVERSA));
        jdbc.update("DELETE FROM observability.curadoria_decisao WHERE chave LIKE 'it %'", Map.of());
    }

    private void mensagem(String texto, String intent, double confianca, boolean fallback) {
        eventos.save(FlowEvent.builder("DIALOGFLOW", "detect_intent", "SUCCESS")
                .correlation(CONVERSA, CONVERSA)
                .intent(intent, "")
                .detail("texto", texto)
                .detail("confianca", confianca)
                .detail("fallback", fallback)
                .build());
    }

    @Test
    void filaAgrupaFalasPendentesERetiraAsDecididas() {
        mensagem("it quero exame de sangue", "Default Fallback Intent", 1.0, true);
        mensagem("IT quero exame de sangue ", "Default Fallback Intent", 1.0, true);
        mensagem("it marca pra mim", "acao.agenda.agendar", 0.41, false);
        mensagem("it oi", "texto.saudacao", 0.98, false);

        assertThat(curadoria.fila(7, 0.6))
                .filteredOn(f -> f.texto().toLowerCase().startsWith("it "))
                .extracting(Curadoria.Frase::vezes, Curadoria.Frase::fallback)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2L, true),
                        org.assertj.core.groups.Tuple.tuple(1L, false));

        curadoria.registrar(new Curadoria.Decisao("it quero exame de sangue", Curadoria.Acao.FORA_ESCOPO, null, null, "u"));

        assertThat(curadoria.fila(7, 0.6))
                .extracting(Curadoria.Frase::texto)
                .filteredOn(t -> t.toLowerCase().startsWith("it "))
                .containsExactly("it marca pra mim");

        Curadoria.Indicadores ind = curadoria.indicadores(7, 0.6);
        assertThat(ind.mensagens()).isGreaterThanOrEqualTo(4);
        assertThat(ind.fallback()).isGreaterThanOrEqualTo(2);
        assertThat(ind.porDia()).isNotEmpty();
        assertThat(ind.intents()).extracting(Curadoria.IntentUso::intent).contains("texto.saudacao");
    }
}
