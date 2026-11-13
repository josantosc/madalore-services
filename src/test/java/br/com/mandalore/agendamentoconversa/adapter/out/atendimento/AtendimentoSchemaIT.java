package br.com.mandalore.agendamentoconversa.adapter.out.atendimento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Sobe os schemas agenda, acesso e atendimento (nessa ordem, como em PostgresObservabilityConfig) num banco descartável
 * e confere as constraints do schema atendimento. Os dois primeiros não dependem do schema observability.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class AtendimentoSchemaIT {

    private static final String BASE = envOu("POSTGRES_JDBC_BASE_URL", "jdbc:postgresql://localhost:5432/");
    private static final String USUARIO = envOu("POSTGRES_USER", "postgres");
    private static final String SENHA = envOu("POSTGRES_PASSWORD", "postgres");

    private final String banco = "atendimento_schema_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final AtomicInteger sequencia = new AtomicInteger();
    private JdbcTemplate jdbc;

    @BeforeEach
    void criarBanco() throws Exception {
        executarNoAdmin("CREATE DATABASE " + banco);
        DataSource ds = new DriverManagerDataSource(BASE + banco, USUARIO, SENHA);
        jdbc = new JdbcTemplate(ds);
        subirSchemas(ds);
    }

    @AfterEach
    void removerBanco() throws Exception {
        if (jdbc != null) {
            executarNoAdmin("DROP DATABASE IF EXISTS " + banco + " WITH (FORCE)");
        }
    }

    @Test
    void subidaDuplaDoSchemaEIdempotente() {
        DataSource ds = jdbc.getDataSource();

        assertThatCode(() -> subirSchemas(ds)).doesNotThrowAnyException();
        assertThatCode(() -> subirSchemas(ds)).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'atendimento'", Integer.class))
                .isEqualTo(12);
    }

    @Test
    void receitaLiberadaSemValidadeERecusada() {
        long atendimento = novoAtendimento("AGUARDANDO");

        assertThatThrownBy(() -> novaReceita(atendimento, "dt_liberacao, dt_validade", "CURRENT_TIMESTAMP, NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> novaReceita(atendimento, "dt_liberacao, dt_validade", "CURRENT_TIMESTAMP, CURRENT_DATE"))
                .doesNotThrowAnyException();
    }

    @Test
    void anamneseInativadaSemJustificativaERecusada() {
        long atendimento = novoAtendimento("AGUARDANDO");

        assertThatThrownBy(() -> novaAnamnese(atendimento, "I", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> novaAnamnese(atendimento, "I", "Registro duplicado")).doesNotThrowAnyException();
    }

    @Test
    void evolucaoInativadaSemJustificativaERecusada() {
        long atendimento = novoAtendimento("AGUARDANDO");

        assertThatThrownBy(() -> novaEvolucao(atendimento, "I", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> novaEvolucao(atendimento, "I", "Texto no paciente errado")).doesNotThrowAnyException();
    }

    @Test
    void diagnosticoInativadoSemJustificativaERecusado() {
        long atendimento = novoAtendimento("AGUARDANDO");

        assertThatThrownBy(() -> novoDiagnostico(atendimento, "J00", "SECUNDARIO", "I", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> novoDiagnostico(atendimento, "J00", "SECUNDARIO", "I", "CID incorreto")).doesNotThrowAnyException();
    }

    @Test
    void receitaInativadaSemJustificativaERecusada() {
        long atendimento = novoAtendimento("AGUARDANDO");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO atendimento.med_receita (conta_id, nr_atendimento, paciente_id, profissional_id, "
                + "dt_receita, ie_situacao, dt_inativacao, nm_usuario) "
                + "SELECT conta_id, nr_atendimento, paciente_id, profissional_id, CURRENT_TIMESTAMP, 'I', CURRENT_TIMESTAMP, 'it' "
                + "FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?", atendimento))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void segundoDiagnosticoPrincipalAtivoERecusado() {
        long atendimento = novoAtendimento("AGUARDANDO");
        novoDiagnostico(atendimento, "J00", "PRINCIPAL", "A", null);

        assertThatThrownBy(() -> novoDiagnostico(atendimento, "J01", "PRINCIPAL", "A", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> novoDiagnostico(atendimento, "J02", "SECUNDARIO", "A", null)).doesNotThrowAnyException();
    }

    @Test
    void doisAtendimentosNaoCanceladosNoMesmoAgendamentoSaoRecusados() {
        long agendamento = novoAgendamento();
        long primeiro = novoAtendimentoDoAgendamento(agendamento, "AGUARDANDO");

        assertThatThrownBy(() -> novoAtendimentoDoAgendamento(agendamento, "AGUARDANDO"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE atendimento.atendimento_paciente SET ie_status_atendimento = 'CANCELADO', dt_cancelamento = CURRENT_TIMESTAMP, "
                + "ds_motivo_cancelamento = 'Chegada desfeita' WHERE nr_atendimento = ?", primeiro);
        assertThatCode(() -> novoAtendimentoDoAgendamento(agendamento, "AGUARDANDO")).doesNotThrowAnyException();
    }

    @Test
    void doisAtendimentosEmConsultaDoMesmoProfissionalSaoRecusados() {
        long profissional = novoProfissional();
        novoAtendimento(profissional, "EM_CONSULTA");

        assertThatThrownBy(() -> novoAtendimento(profissional, "EM_CONSULTA")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> novoAtendimento(profissional, "AGUARDANDO")).doesNotThrowAnyException();
    }

    // --- dados de apoio ---

    private long novoProfissional() {
        return jdbc.queryForObject("INSERT INTO agenda.profissional (conta_id, nome) VALUES ('it', ?) RETURNING id", Long.class,
                "Dr. " + sequencia.incrementAndGet());
    }

    private long novoAgendamento() {
        return novoAgendamento(novoProfissional());
    }

    private long novoAgendamento(long profissional) {
        long especialidade = jdbc.queryForObject("INSERT INTO agenda.especialidade (conta_id, nome) VALUES ('it', ?) RETURNING id",
                Long.class, "Esp " + sequencia.incrementAndGet());
        long paciente = jdbc.queryForObject("INSERT INTO agenda.paciente (conta_id, cpf, nascimento, nome) "
                + "VALUES ('it', ?, DATE '1988-10-19', 'Maria') RETURNING id", Long.class, String.format("%011d", sequencia.incrementAndGet()));
        return jdbc.queryForObject("INSERT INTO agenda.agendamento (conta_id, profissional_id, especialidade_id, paciente_id, inicio, fim, "
                + "status, origem) VALUES ('it', ?, ?, ?, TIMESTAMP '2030-01-07 09:00' + ? * INTERVAL '1 hour', "
                + "TIMESTAMP '2030-01-07 09:30' + ? * INTERVAL '1 hour', 'MARCADO', 'RECEPCAO') RETURNING id",
                Long.class, profissional, especialidade, paciente, sequencia.get(), sequencia.get());
    }

    private long novoAtendimento(String status) {
        return novoAtendimentoDoAgendamento(novoAgendamento(), status);
    }

    private long novoAtendimento(long profissional, String status) {
        return novoAtendimentoDoAgendamento(novoAgendamento(profissional), status);
    }

    private long novoAtendimentoDoAgendamento(long agendamento, String status) {
        return jdbc.queryForObject("INSERT INTO atendimento.atendimento_paciente (conta_id, agendamento_id, paciente_id, profissional_id, "
                + "especialidade_id, ie_status_atendimento, dt_entrada, dt_atend_medico, nm_usuario_entrada) "
                + "SELECT conta_id, id, paciente_id, profissional_id, especialidade_id, ?, CURRENT_TIMESTAMP, "
                + "CASE WHEN ? = 'EM_CONSULTA' THEN CURRENT_TIMESTAMP END, 'it' FROM agenda.agendamento WHERE id = ? RETURNING nr_atendimento",
                Long.class, status, status, agendamento);
    }

    private void novaReceita(long atendimento, String colunas, String valores) {
        jdbc.update("INSERT INTO atendimento.med_receita (conta_id, nr_atendimento, paciente_id, profissional_id, dt_receita, nm_usuario, "
                + colunas + ") SELECT conta_id, nr_atendimento, paciente_id, profissional_id, CURRENT_TIMESTAMP, 'it', " + valores
                + " FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?", atendimento);
    }

    private void novaAnamnese(long atendimento, String situacao, String justificativa) {
        jdbc.update("INSERT INTO atendimento.anamnese_paciente (conta_id, nr_atendimento, paciente_id, profissional_id, dt_registro, "
                + "ie_situacao, dt_inativacao, ds_justificativa, nm_usuario) "
                + "SELECT conta_id, nr_atendimento, paciente_id, profissional_id, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, 'it' "
                + "FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?", situacao, justificativa, atendimento);
    }

    private void novaEvolucao(long atendimento, String situacao, String justificativa) {
        jdbc.update("INSERT INTO atendimento.evolucao_paciente (conta_id, nr_atendimento, paciente_id, profissional_id, dt_evolucao, "
                + "ie_situacao, dt_inativacao, ds_justificativa, nm_usuario) "
                + "SELECT conta_id, nr_atendimento, paciente_id, profissional_id, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, 'it' "
                + "FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?", situacao, justificativa, atendimento);
    }

    private void novoDiagnostico(long atendimento, String cid, String tipo, String situacao, String justificativa) {
        jdbc.update("INSERT INTO atendimento.diagnostico_doenca (conta_id, nr_atendimento, paciente_id, profissional_id, cd_doenca, "
                + "ie_tipo_diagnostico, dt_diagnostico, ie_situacao, dt_inativacao, ds_justificativa, nm_usuario) "
                + "SELECT conta_id, nr_atendimento, paciente_id, profissional_id, ?, ?, CURRENT_TIMESTAMP, ?, "
                + "CASE WHEN ? = 'I' THEN CURRENT_TIMESTAMP END, ?, 'it' "
                + "FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?", cid, tipo, situacao, situacao, justificativa, atendimento);
    }

    private static void subirSchemas(DataSource ds) {
        new ResourceDatabasePopulator(new ClassPathResource("db/agenda/schema.sql"), new ClassPathResource("db/acesso/schema.sql"),
                new ClassPathResource("db/atendimento/schema.sql")).execute(ds);
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
