package br.com.mandalore.agendamentoconversa.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;

@SpringBootTest
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
@Sql(
        scripts = "classpath:db/local-postgres/data.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@Sql(
        scripts = "classpath:db/local-postgres/data.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD
)
class PostgresAgendamentoAdapterIT {

    @Autowired
    private PostgresAgendamentoAdapter adapter;

    @Autowired
    @Qualifier("tasyJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void executaCicloCompletoSemPermitirDuplaReserva() {
        PedidoAgendar pedido = pedido("agendar-6001", "1001", "3001", 1, 9);

        assertThat(adapter.agendar(pedido).codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(status("6001")).isEqualTo("N");
        assertThat(paciente("6001")).isEqualTo("1001");

        assertThat(adapter.agendar(pedido).codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.confirmar("confirmar-6001", "1001", "6001").codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(status("6001")).isEqualTo("CN");

        assertThat(adapter.cancelar("cancelar-6001", "1001", "6001").codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(status("6001")).isEqualTo("L");
        assertThat(paciente("6001")).isNull();

        PedidoAgendar primeiraReserva = pedido("agendar-6002-a", "1001", "3001", 1, 14);
        PedidoAgendar segundaReserva = pedido("agendar-6002-b", "9999", "3001", 1, 14);
        assertThat(adapter.agendar(primeiraReserva).codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.agendar(segundaReserva).codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
        assertThat(paciente("6002")).isEqualTo("1001");

        PedidoAgendar novoHorario = pedido("novo-6003", "1001", "3002", 2, 10);
        assertThat(adapter.remarcar("remarcar-6101-6003", "1001", "6101", novoHorario).codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(status("6101")).isEqualTo("L");
        assertThat(status("6003")).isEqualTo("N");
        assertThat(paciente("6003")).isEqualTo("1001");

        PedidoAgendar semCadastro = new PedidoAgendar(
                "agendar-6004-sem-cadastro",
                null,
                "3002",
                LocalDate.now().plusDays(3).atTime(15, 0),
                "12345678901",
                LocalDate.of(1985, 7, 12),
                "Paciente Sem Cadastro",
                true
        );
        assertThat(adapter.agendar(semCadastro).codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(status("6004")).isEqualTo("N");
        assertThat(paciente("6004")).isNull();
        assertThat(nomePaciente("6004")).isEqualTo("Paciente Sem Cadastro");
        assertThat(quantidadePessoaPorCpf("12345678901")).isZero();
    }

    @Test
    void agendaExameEmAgendaPacienteSemPermitirDuplaReserva() {
        ProcedimentoExame exame = new ProcedimentoExame("5002", "40901114", "1", "EXAME-TESTE");
        PedidoAgendar pedido = new PedidoAgendar(
                "agendar-exame-7002",
                "1001",
                "4001",
                LocalDate.now().plusDays(2).atTime(13, 0),
                "52998224725",
                LocalDate.of(1990, 5, 20),
                "Paciente Teste",
                false,
                exame
        );

        assertThat(adapter.agendar(pedido).codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(jdbc.queryForMap(
                """
                SELECT ie_status_agenda, cd_pessoa_fisica, nr_seq_proc_interno
                FROM tasy_test.agenda_paciente
                WHERE nr_sequencia = 7002
                """,
                java.util.Map.of()
        )).containsEntry("ie_status_agenda", "N")
                .containsEntry("cd_pessoa_fisica", "1001")
                .containsEntry("nr_seq_proc_interno", new java.math.BigDecimal("5002"));

        PedidoAgendar outro = new PedidoAgendar(
                "agendar-exame-7002-b",
                "1001",
                "4001",
                pedido.dataAgenda(),
                "52998224725",
                LocalDate.of(1990, 5, 20),
                "Paciente Teste",
                false,
                exame
        );
        assertThat(adapter.agendar(outro).codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
    }

    private PedidoAgendar pedido(
            String chave,
            String paciente,
            String agenda,
            int dias,
            int hora
    ) {
        return new PedidoAgendar(
                chave,
                paciente,
                agenda,
                LocalDate.now().plusDays(dias).atTime(hora, 0),
                "52998224725",
                LocalDate.of(1990, 5, 20),
                "Paciente Teste",
                false
        );
    }

    private String status(String sequencia) {
        return jdbc.queryForObject(
                "SELECT ie_status_agenda FROM tasy_test.agenda_consulta WHERE nr_sequencia = :sequencia",
                java.util.Map.of("sequencia", new java.math.BigDecimal(sequencia)),
                String.class
        );
    }

    private String paciente(String sequencia) {
        return jdbc.queryForObject(
                "SELECT cd_pessoa_fisica FROM tasy_test.agenda_consulta WHERE nr_sequencia = :sequencia",
                java.util.Map.of("sequencia", new java.math.BigDecimal(sequencia)),
                String.class
        );
    }

    private String nomePaciente(String sequencia) {
        return jdbc.queryForObject(
                "SELECT nm_paciente FROM tasy_test.agenda_consulta WHERE nr_sequencia = :sequencia",
                java.util.Map.of("sequencia", new java.math.BigDecimal(sequencia)),
                String.class
        );
    }

    private Integer quantidadePessoaPorCpf(String cpf) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM tasy_test.pessoa_fisica
                WHERE regexp_replace(nr_cpf, '[^0-9]', '', 'g') = :cpf
                """,
                java.util.Map.of("cpf", cpf),
                Integer.class
        );
    }
}
