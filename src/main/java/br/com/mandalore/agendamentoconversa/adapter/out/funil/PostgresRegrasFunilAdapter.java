package br.com.mandalore.agendamentoconversa.adapter.out.funil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.RegrasFunilPort;

/**
 * Cadência por conta na tabela funil.regra; a inicial vem de FUNIL_CADENCIA, FUNIL_DIAS_ENCERRAMENTO e
 * FUNIL_SABADO_UTIL. As regras gravadas ficam em memória por {@link #VALIDADE}, porque cada mensagem do Chatwoot
 * consulta a cadência; com mais de uma réplica, a gravação chega às outras nesse prazo.
 */
@Component
@ConditionalOnProperty(name = {"funil.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PostgresRegrasFunilAdapter implements RegrasFunilPort {

    static final Duration VALIDADE = Duration.ofMinutes(1);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final Clock relogio;
    private final Funil.Regras padrao;
    private volatile Map<String, Funil.RegrasSalvas> salvas;
    private volatile Instant lidasEm = Instant.MIN;

    @Autowired
    public PostgresRegrasFunilAdapter(
            @Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Qualifier("observabilityDataSource") DataSource dataSource,
            @Value("${funil.cadencia:1,2,2}") String cadencia,
            @Value("${funil.dias-encerramento:2}") int diasEncerramento,
            @Value("${funil.sabado-util:false}") boolean sabadoUtil
    ) {
        this(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), Clock.systemUTC(),
                cadencia, diasEncerramento, sabadoUtil);
    }

    PostgresRegrasFunilAdapter(NamedParameterJdbcTemplate jdbc, TransactionTemplate transacao, Clock relogio,
                               String cadencia, int diasEncerramento, boolean sabadoUtil) {
        this.jdbc = jdbc;
        this.transacao = transacao;
        this.relogio = relogio;
        try {
            this.padrao = new Funil.Regras(cadencia(cadencia), diasEncerramento, sabadoUtil, Set.of());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("FUNIL_CADENCIA / FUNIL_DIAS_ENCERRAMENTO: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Funil.Regras padrao() {
        return padrao;
    }

    @Override
    public Map<String, Funil.RegrasSalvas> salvas() {
        Map<String, Funil.RegrasSalvas> atuais = salvas;
        if (atuais == null || lidasEm.plus(VALIDADE).isBefore(relogio.instant())) {
            atuais = ler();
            salvas = atuais;
            lidasEm = relogio.instant();
        }
        return atuais;
    }

    @Override
    public void salvar(String conta, Funil.Regras regras, String usuario) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("conta", conta)
                .addValue("cadencia", regras.cadencia().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addValue("encerramento", regras.diasEncerramento())
                .addValue("sabado", regras.sabadoUtil())
                .addValue("feriados", regras.feriados().stream().sorted().map(LocalDate::toString).collect(Collectors.joining(",")))
                .addValue("usuario", usuario == null || usuario.isBlank() ? "mandalore" : limitar(usuario, 200));
        transacao.executeWithoutResult(status -> {
            String anterior = jdbc.query("SELECT * FROM funil.regra WHERE conta_id = :conta FOR UPDATE", params,
                    (rs, i) -> descrever(regras(rs.getString("cadencia"), rs.getInt("dias_encerramento"),
                            rs.getBoolean("sabado_util"), rs.getString("feriados")))).stream().findFirst().orElse(null);
            jdbc.update("""
                    INSERT INTO funil.regra (conta_id, cadencia, dias_encerramento, sabado_util, feriados, atualizada_por)
                    VALUES (:conta, :cadencia, :encerramento, :sabado, :feriados, :usuario)
                    ON CONFLICT (conta_id) DO UPDATE SET cadencia = EXCLUDED.cadencia,
                        dias_encerramento = EXCLUDED.dias_encerramento, sabado_util = EXCLUDED.sabado_util,
                        feriados = EXCLUDED.feriados, atualizada_por = EXCLUDED.atualizada_por,
                        atualizada_em = CURRENT_TIMESTAMP
                    """, params);
            jdbc.update("""
                    INSERT INTO funil.regra_evento (conta_id, usuario, anterior, nova)
                    VALUES (:conta, :usuario, :anterior, :nova)
                    """, new MapSqlParameterSource(params.getValues())
                    .addValue("anterior", anterior)
                    .addValue("nova", descrever(regras)));
        });
        salvas = null;
    }

    private Map<String, Funil.RegrasSalvas> ler() {
        Map<String, Funil.RegrasSalvas> contas = new HashMap<>();
        jdbc.query("SELECT * FROM funil.regra", rs -> {
            contas.put(rs.getString("conta_id"), new Funil.RegrasSalvas(
                    regras(rs.getString("cadencia"), rs.getInt("dias_encerramento"), rs.getBoolean("sabado_util"),
                            rs.getString("feriados")),
                    rs.getString("atualizada_por"), rs.getTimestamp("atualizada_em").toInstant()));
        });
        return Map.copyOf(contas);
    }

    private static Funil.Regras regras(String cadencia, int diasEncerramento, boolean sabadoUtil, String feriados) {
        Set<LocalDate> dias = feriados == null ? Set.of() : Arrays.stream(feriados.split(",")).map(String::trim).filter(t -> !t.isEmpty())
                .map(LocalDate::parse).collect(Collectors.toSet());
        return new Funil.Regras(cadencia(cadencia), diasEncerramento, sabadoUtil, dias);
    }

    static List<Integer> cadencia(String texto) {
        try {
            return Arrays.stream(texto.split(",")).map(String::trim).filter(t -> !t.isEmpty()).map(Integer::valueOf).toList();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("use dias úteis separados por vírgula, por exemplo 1,2,2", ex);
        }
    }

    /** Texto do histórico, legível para quem audita. */
    static String descrever(Funil.Regras regras) {
        return "contatos " + regras.cadencia().stream().map(String::valueOf).collect(Collectors.joining(","))
                + "; encerra em " + regras.diasEncerramento()
                + "; sábado " + (regras.sabadoUtil() ? "útil" : "não útil")
                + "; feriados locais " + (regras.feriados().isEmpty() ? "nenhum"
                : regras.feriados().stream().sorted().map(LocalDate::toString).collect(Collectors.joining(",")));
    }

    private static String limitar(String texto, int limite) {
        return texto.length() <= limite ? texto : texto.substring(0, limite);
    }
}
