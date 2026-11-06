package br.com.mandalore.agendamentoconversa.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;

@SpringBootTest(properties = "relatorios.enabled=true")
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresRelatorioAdapterIT {

    private static final String CONVERSA = "relatorio-it";
    /** Dia fixo no passado: nenhum evento real cai na janela. */
    private static final Instant INICIO = Instant.parse("2001-01-01T03:00:00Z");
    private static final Instant FIM = Instant.parse("2001-01-02T03:00:00Z");

    @Autowired
    private PostgresRelatorioAdapter relatorio;

    @Autowired
    @Qualifier("observabilityJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM observability.flow_event WHERE conversation_id LIKE :c", Map.of("c", CONVERSA + "%"));
    }

    @Test
    void conversaoPorConversa() {
        String marcou = CONVERSA + "-marcou";
        String atendente = CONVERSA + "-atendente";
        String nada = CONVERSA + "-nada";
        for (String conversa : new String[] {marcou, atendente, nada}) {
            evento(conversa, "DIALOGFLOW", "detect_intent", "SUCCESS", "texto.saudacao", null, null, "{\"texto\":\"oi\"}");
        }
        evento(marcou, "MANDALORE", "fulfillment.agendar", "SUCCESS", "acao.agenda.agendar", "RESULTADO_SUCESSO_ESCRITA",
                "sucesso", "{\"escrita\":\"agendar\",\"exame\":false}");
        evento(marcou, "CHATWOOT", "transbordo", "SUCCESS", null, null, null, "{\"equipe\":\"RECEPCAO\"}");
        evento(atendente, "CHATWOOT", "transbordo", "SUCCESS", null, null, null, "{\"equipe\":\"RECEPCAO\"}");
        evento(nada, "MANDALORE", "fulfillment.cancelar", "SUCCESS", "acao.agenda.cancelar", "RESULTADO_SUCESSO_ESCRITA",
                "sucesso", "{\"escrita\":\"cancelar\",\"exame\":false}");

        Relatorio.Conversao c = relatorio.numeros(INICIO, FIM, 0.6).conversao();

        assertThat(c).isEqualTo(new Relatorio.Conversao(3, 1, 1));
        assertThat(c.semDesfecho()).isEqualTo(1);
    }

    @Test
    void contaMensagensEscritasTransbordosOrcamentosEPerguntasFrequentes() {
        evento("DIALOGFLOW", "detect_intent", "SUCCESS", "texto.saudacao", null, null,
                "{\"texto\":\"oi\",\"confianca\":0.9,\"fallback\":false}");
        evento("DIALOGFLOW", "detect_intent", "SUCCESS", "texto.fallback", null, null,
                "{\"texto\":\"xyz relatorio it\",\"confianca\":1,\"fallback\":true}");
        evento("DIALOGFLOW", "detect_intent", "SUCCESS", "acao.agenda.agendar", null, null,
                "{\"texto\":\"marca\",\"confianca\":0.4,\"fallback\":false}");
        evento("MANDALORE", "fulfillment.agendar", "SUCCESS", "acao.agenda.agendar", "RESULTADO_SUCESSO_ESCRITA", "sucesso",
                "{\"escrita\":\"agendar\",\"exame\":false}");
        evento("MANDALORE", "fulfillment.agendar", "SUCCESS", "acao.agenda.agendar", "RESULTADO_SUCESSO_ESCRITA", "sucesso",
                "{\"escrita\":\"agendar\",\"exame\":true}");
        evento("MANDALORE", "fulfillment.agendar", "SUCCESS", "acao.agenda.agendar", "RESULTADO_SUCESSO_ESCRITA", "sucesso",
                "{\"escrita\":\"cancelar\",\"exame\":false}");
        evento("MANDALORE", "fulfillment.endereco", "SUCCESS", "acao.faq.endereco", "RESULTADO_FAQ", "sucesso", "{}");
        evento("MANDALORE", "fulfillment.estacionamento", "SUCCESS", "acao.faq.estacionamento", "RESULTADO_FAQ",
                "faq_sem_resposta", "{}");
        evento("CHATWOOT", "transbordo", "SUCCESS", null, null, null, "{\"equipe\":\"LABORATORIO\"}");
        evento("CHATWOOT", "orcamento.pedido", "SUCCESS", null, null, null, "{}");
        evento("TASY", "agenda.disponibilidade", "ERROR", "acao.agenda.disponibilidade", null, null, "{}");

        Relatorio.Numeros n = relatorio.numeros(INICIO, FIM, 0.6);

        assertThat(n.mensagens()).isEqualTo(3);
        assertThat(n.conversas()).isEqualTo(1);
        assertThat(n.naoEntendidas()).isEqualTo(1);
        assertThat(n.baixaConfianca()).isEqualTo(1);
        assertThat(n.escritas()).containsExactlyInAnyOrderEntriesOf(Map.of("consulta", 1L, "exame", 1L, "cancelar", 1L));
        assertThat(n.faqRespondidas()).isEqualTo(1);
        assertThat(n.faqSemResposta()).containsExactlyEntriesOf(Map.of("estacionamento", 1L));
        assertThat(n.transbordos()).containsExactlyEntriesOf(Map.of("LABORATORIO", 1L));
        assertThat(n.orcamentos()).isEqualTo(1);
        assertThat(n.falhas()).containsExactlyEntriesOf(Map.of("TASY", 1L));
        assertThat(n.pendentes()).isEqualTo(2);
        assertThat(n.conversao()).isEqualTo(new Relatorio.Conversao(1, 1, 0));
    }

    private void evento(String component, String stage, String status, String intent, String eventName,
            String resultado, String details) {
        evento(CONVERSA, component, stage, status, intent, eventName, resultado, details);
    }

    private void evento(String conversa, String component, String stage, String status, String intent, String eventName,
            String resultado, String details) {
        jdbc.update("""
                INSERT INTO observability.flow_event
                    (correlation_id, conversation_id, component, stage, status, intent, event_name, business_result,
                     details, created_at)
                VALUES (:c, :c, :component, :stage, :status, :intent, :evento, :resultado, CAST(:details AS jsonb),
                        CAST(:quando AS timestamptz))
                """, new MapSqlParameterSource()
                .addValue("c", conversa)
                .addValue("component", component)
                .addValue("stage", stage)
                .addValue("status", status)
                .addValue("intent", intent)
                .addValue("evento", eventName)
                .addValue("resultado", resultado)
                .addValue("details", details)
                .addValue("quando", "2001-01-01T12:00:00Z"));
    }
}
