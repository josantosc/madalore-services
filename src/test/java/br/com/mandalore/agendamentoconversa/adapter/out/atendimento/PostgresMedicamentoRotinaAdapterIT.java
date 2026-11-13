package br.com.mandalore.agendamentoconversa.adapter.out.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;

@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresMedicamentoRotinaAdapterIT {

    private static final String BASE = envOu("POSTGRES_JDBC_BASE_URL", "jdbc:postgresql://localhost:5432/");
    private static final String USUARIO = envOu("POSTGRES_USER", "postgres");
    private static final String SENHA = envOu("POSTGRES_PASSWORD", "postgres");
    private static final List<Atendimento.MedicamentoRotina> PADRAO = List.of(
            new Atendimento.MedicamentoRotina(null, "Dipirona", "500 mg comprimido", "1 cp de 6/6 horas", "Oral", false),
            new Atendimento.MedicamentoRotina(null, "Losartana", "50 mg comprimido", "1 cp ao dia", "Oral", true));

    private final String banco = "medicamento_rotina_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private JdbcTemplate jdbc;
    private PostgresMedicamentoRotinaAdapter adapter;

    @BeforeEach
    void criarBanco() throws Exception {
        executarNoAdmin("CREATE DATABASE " + banco);
        DataSource ds = new DriverManagerDataSource(BASE + banco, USUARIO, SENHA);
        jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/agenda/schema.sql"), new ClassPathResource("db/acesso/schema.sql"),
                new ClassPathResource("db/atendimento/schema.sql")).execute(ds);
        adapter = new PostgresMedicamentoRotinaAdapter(new NamedParameterJdbcTemplate(jdbc), ds);
    }

    @AfterEach
    void removerBanco() throws Exception {
        if (jdbc != null) {
            executarNoAdmin("DROP DATABASE IF EXISTS " + banco + " WITH (FORCE)");
        }
    }

    @Test
    void listaPadraoEntraUmaVezEExcluirTudoNaoATrazDeVolta() {
        adapter.semearSeNova("7", PADRAO);
        adapter.semearSeNova("7", PADRAO);

        List<Atendimento.MedicamentoRotina> itens = adapter.listar("7");
        assertThat(itens).extracting(Atendimento.MedicamentoRotina::medicamento).containsExactly("Dipirona", "Losartana");
        assertThat(itens.get(1).usoContinuo()).isTrue();
        assertThat(adapter.listar("8")).isEmpty();

        itens.forEach(i -> assertThat(adapter.excluir("7", i.id())).isTrue());
        adapter.semearSeNova("7", PADRAO);

        assertThat(adapter.listar("7")).isEmpty();
        assertThat(adapter.incluirFaltantes("7", PADRAO, "gestor")).isEqualTo(2);
        assertThat(adapter.incluirFaltantes("7", PADRAO, "gestor")).isZero();
    }

    @Test
    void salvarCriaAlteraERecusaRepetidoEOutraConta() {
        Atendimento.Operacao criado = adapter.salvar("7",
                new Atendimento.MedicamentoRotina(null, "Amoxicilina", "500 mg cápsula", "1 cp 8/8h", "Oral", false), "gestor");
        assertThat(criado.codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        assertThat(adapter.salvar("7", new Atendimento.MedicamentoRotina(null, " amoxicilina", "500 MG cápsula", "", "", false), "g")
                .codigo()).isEqualTo(CodigoNegocio.MEDICAMENTO_DUPLICADO);
        assertThat(adapter.salvar("8", new Atendimento.MedicamentoRotina(criado.id(), "Amoxicilina", "", "", "", false), "g")
                .codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.salvar("7", new Atendimento.MedicamentoRotina(criado.id(), "Amoxicilina", "500 mg cápsula",
                "1 cp 8/8h por 10 dias", "Oral", false), "gestor").codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        assertThat(adapter.listar("7")).singleElement().extracting(Atendimento.MedicamentoRotina::posologia)
                .isEqualTo("1 cp 8/8h por 10 dias");
        assertThat(adapter.excluir("8", criado.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT atualizado_por FROM atendimento.medicamento_rotina WHERE id = ?", String.class,
                criado.id())).isEqualTo("gestor");
    }

    private void executarNoAdmin(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(BASE + "postgres", USUARIO, SENHA); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String envOu(String nome, String padrao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? padrao : valor;
    }
}
