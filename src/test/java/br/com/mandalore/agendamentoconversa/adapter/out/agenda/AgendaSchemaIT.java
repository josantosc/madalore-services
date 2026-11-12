package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Sobe o schema da agenda duas vezes sobre um banco no formato antigo (4 status e índice uk_agendamento_ativo).
 * Usa um banco descartável criado só para o teste.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class AgendaSchemaIT {

    private static final String BASE = envOu("POSTGRES_JDBC_BASE_URL", "jdbc:postgresql://localhost:5432/");
    private static final String USUARIO = envOu("POSTGRES_USER", "postgres");
    private static final String SENHA = envOu("POSTGRES_PASSWORD", "postgres");

    private final String banco = "agenda_schema_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private JdbcTemplate jdbc;

    @BeforeEach
    void criarBancoNoFormatoAntigo() throws Exception {
        executarNoAdmin("CREATE DATABASE " + banco);
        DataSource ds = new DriverManagerDataSource(BASE + banco, USUARIO, SENHA);
        jdbc = new JdbcTemplate(ds);

        subirSchema(ds);
        // Volta ao formato anterior ao atendimento clínico.
        jdbc.execute("ALTER TABLE agenda.agendamento DROP CONSTRAINT agendamento_status_check");
        jdbc.execute("ALTER TABLE agenda.agendamento ADD CONSTRAINT agendamento_status_check "
                + "CHECK (status IN ('MARCADO', 'CONFIRMADO', 'CANCELADO', 'REMARCADO'))");
        jdbc.execute("DROP INDEX agenda.uk_agendamento_ocupa_horario");
        jdbc.execute("CREATE UNIQUE INDEX uk_agendamento_ativo ON agenda.agendamento (conta_id, profissional_id, inicio) "
                + "WHERE status IN ('MARCADO', 'CONFIRMADO')");
    }

    @AfterEach
    void removerBanco() throws Exception {
        if (jdbc != null && jdbc.getDataSource() instanceof DriverManagerDataSource) {
            // Sem pool: nenhuma conexão fica aberta além das de cada operação.
            executarNoAdmin("DROP DATABASE IF EXISTS " + banco + " WITH (FORCE)");
        }
    }

    @Test
    void subidaDuplaSobreBancoAntigoMigraEFicaIdempotente() {
        DataSource ds = jdbc.getDataSource();

        assertThatCode(() -> subirSchema(ds)).doesNotThrowAnyException();
        assertThatCode(() -> subirSchema(ds)).doesNotThrowAnyException();

        assertThat(indices()).contains("uk_agendamento_ocupa_horario").doesNotContain("uk_agendamento_ativo");
        assertThat(jdbc.queryForObject("SELECT pg_get_indexdef('agenda.uk_agendamento_ocupa_horario'::regclass)", String.class))
                .contains("AGUARDANDO", "EM_CONSULTA", "EXECUTADO").doesNotContain("CANCELADO");

        long agendamento = novoAgendamento("2030-01-07 09:00", "AGUARDANDO");
        assertThat(agendamento).isPositive();
        for (String status : List.of("EM_CONSULTA", "EXECUTADO")) {
            assertThatCode(() -> atualizarStatus(agendamento, status)).doesNotThrowAnyException();
        }
    }

    @Test
    void horarioFicaOcupadoAteExecutadoMasCanceladoLibera() {
        subirSchema(jdbc.getDataSource());
        subirSchema(jdbc.getDataSource());

        novoAgendamento("2030-01-07 10:00", "EXECUTADO");

        assertThatThrownBy(() -> novoAgendamento("2030-01-07 10:00", "MARCADO")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> novoAgendamento("2030-01-07 10:00", "CANCELADO")).doesNotThrowAnyException();
        assertThatThrownBy(() -> novoAgendamento("2030-01-07 11:00", "INEXISTENTE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> indices() {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'agenda' AND tablename = 'agendamento'", String.class);
    }

    private long novoAgendamento(String inicio, String status) {
        Long profissional = jdbc.queryForList("SELECT id FROM agenda.profissional WHERE conta_id = 'it'", Long.class).stream().findFirst()
                .orElseGet(() -> jdbc.queryForObject("INSERT INTO agenda.profissional (conta_id, nome) VALUES ('it', 'Dra. Ana') RETURNING id", Long.class));
        Long especialidade = jdbc.queryForList("SELECT id FROM agenda.especialidade WHERE conta_id = 'it'", Long.class).stream().findFirst()
                .orElseGet(() -> jdbc.queryForObject("INSERT INTO agenda.especialidade (conta_id, nome) VALUES ('it', 'Clínica') RETURNING id", Long.class));
        Long paciente = jdbc.queryForList("SELECT id FROM agenda.paciente WHERE conta_id = 'it'", Long.class).stream().findFirst()
                .orElseGet(() -> jdbc.queryForObject("INSERT INTO agenda.paciente (conta_id, cpf, nascimento, nome) "
                        + "VALUES ('it', '52998224725', DATE '1988-10-19', 'Maria') RETURNING id", Long.class));
        return jdbc.queryForObject("INSERT INTO agenda.agendamento (conta_id, profissional_id, especialidade_id, paciente_id, inicio, fim, status, origem) "
                + "VALUES ('it', ?, ?, ?, CAST(? AS timestamp), CAST(? AS timestamp) + INTERVAL '30 minutes', ?, 'RECEPCAO') RETURNING id",
                Long.class, profissional, especialidade, paciente, inicio, inicio, status);
    }

    private void atualizarStatus(long id, String status) {
        jdbc.update("UPDATE agenda.agendamento SET status = ? WHERE id = ?", status, id);
    }

    private static void subirSchema(DataSource ds) {
        new ResourceDatabasePopulator(new ClassPathResource("db/agenda/schema.sql")).execute(ds);
    }

    private static void executarNoAdmin(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(BASE + "postgres", USUARIO, SENHA); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String envOu(String nome, String padrao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? padrao : valor;
    }
}
