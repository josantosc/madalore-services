package br.com.mandalore.agendamentoconversa.adapter.out.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.RelatorioPort;

@Component
@ConditionalOnProperty(name = {"relatorios.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PostgresRelatorioAdapter implements RelatorioPort {

    private static final String PERIODO = " AND created_at >= :inicio AND created_at < :fim";

    private static final String CONVERSAS = """
            SELECT count(*) AS mensagens,
                   count(DISTINCT conversation_id) AS conversas,
                   count(*) FILTER (WHERE COALESCE((details->>'fallback')::boolean, false)) AS nao_entendidas,
                   count(*) FILTER (WHERE (details->>'confianca')::numeric < :limite) AS baixa,
                   (SELECT count(*) FROM observability.curadoria_decisao
                     WHERE acao = 'ENSINAR' AND created_at >= :inicio AND created_at < :fim) AS ensinadas
            FROM observability.flow_event
            WHERE component = 'DIALOGFLOW' AND stage = 'detect_intent' AND status = 'SUCCESS'
            """ + PERIODO;

    private static final String PENDENTES = """
            SELECT count(DISTINCT lower(trim(e.details->>'texto')))
            FROM observability.flow_event e
            WHERE e.component = 'DIALOGFLOW' AND e.stage = 'detect_intent' AND e.status = 'SUCCESS'
              AND e.created_at >= CAST(:fim AS timestamptz) - interval '7 days' AND e.created_at < :fim
              AND COALESCE(e.details->>'texto', '') <> ''
              AND (COALESCE((e.details->>'fallback')::boolean, false) OR (e.details->>'confianca')::numeric < :limite)
              AND NOT EXISTS (
                  SELECT 1 FROM observability.curadoria_decisao d WHERE d.chave = lower(trim(e.details->>'texto'))
              )
            """;

    private static final String FAQ = """
            SELECT substring(intent from 10) AS chave, business_result AS resultado, count(*) AS vezes
            FROM observability.flow_event
            WHERE component = 'MANDALORE' AND stage LIKE 'fulfillment.%' AND intent LIKE 'acao.faq.%'
              AND business_result IN ('sucesso', 'faq_sem_resposta')
            """ + PERIODO + " GROUP BY 1, 2";

    private static final String ESCRITAS = """
            SELECT CASE WHEN details->>'escrita' = 'agendar'
                        THEN CASE WHEN COALESCE((details->>'exame')::boolean, false) THEN 'exame' ELSE 'consulta' END
                        ELSE details->>'escrita' END AS chave,
                   count(*) AS vezes
            FROM observability.flow_event
            WHERE component = 'MANDALORE' AND stage LIKE 'fulfillment.%'
              AND event_name = 'RESULTADO_SUCESSO_ESCRITA' AND details->>'escrita' IS NOT NULL
            """ + PERIODO + " GROUP BY 1";

    private static final String TRANSBORDOS = """
            SELECT COALESCE(details->>'equipe', 'RECEPCAO') AS chave, count(*) AS vezes
            FROM observability.flow_event
            WHERE component = 'CHATWOOT' AND stage = 'transbordo'
            """ + PERIODO + " GROUP BY 1";

    private static final String ORCAMENTOS = """
            SELECT count(*) FROM observability.flow_event
            WHERE component = 'CHATWOOT' AND stage = 'orcamento.pedido'
            """ + PERIODO;

    private static final String FALHAS = """
            SELECT component AS chave, count(*) AS vezes
            FROM observability.flow_event
            WHERE status = 'ERROR'
            """ + PERIODO + " GROUP BY 1";

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresRelatorioAdapter(@Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Relatorio.Numeros numeros(Instant inicio, Instant fim, double limiteConfianca) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("inicio", OffsetDateTime.ofInstant(inicio, ZoneOffset.UTC))
                .addValue("fim", OffsetDateTime.ofInstant(fim, ZoneOffset.UTC))
                .addValue("limite", BigDecimal.valueOf(limiteConfianca));
        Map<String, Long> faqSemResposta = new LinkedHashMap<>();
        long[] faqRespondidas = {0};
        jdbc.query(FAQ + " ORDER BY 3 DESC", params, rs -> {
            if ("faq_sem_resposta".equals(rs.getString("resultado"))) {
                faqSemResposta.put(rs.getString("chave"), rs.getLong("vezes"));
            } else {
                faqRespondidas[0] += rs.getLong("vezes");
            }
        });
        Long pendentes = jdbc.queryForObject(PENDENTES, params, Long.class);
        Long orcamentos = jdbc.queryForObject(ORCAMENTOS, params, Long.class);
        Map<String, Long> escritas = contagem(ESCRITAS, params);
        Map<String, Long> transbordos = contagem(TRANSBORDOS, params);
        Map<String, Long> falhas = contagem(FALHAS, params);
        return jdbc.queryForObject(CONVERSAS, params, (rs, i) -> new Relatorio.Numeros(
                rs.getLong("mensagens"),
                rs.getLong("conversas"),
                rs.getLong("nao_entendidas"),
                rs.getLong("baixa"),
                pendentes == null ? 0 : pendentes,
                rs.getLong("ensinadas"),
                faqRespondidas[0],
                faqSemResposta,
                escritas,
                transbordos,
                orcamentos == null ? 0 : orcamentos,
                falhas));
    }

    private Map<String, Long> contagem(String sql, MapSqlParameterSource params) {
        Map<String, Long> mapa = new LinkedHashMap<>();
        jdbc.query(sql + " ORDER BY 2 DESC", params, rs -> {
            mapa.put(rs.getString("chave"), rs.getLong("vezes"));
        });
        return mapa;
    }
}
