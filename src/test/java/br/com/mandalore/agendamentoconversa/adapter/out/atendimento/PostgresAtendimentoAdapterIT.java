package br.com.mandalore.agendamentoconversa.adapter.out.atendimento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import br.com.mandalore.agendamentoconversa.application.domain.exception.AcessoNegadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.OrigemAgendaPort;
import br.com.mandalore.agendamentoconversa.application.domain.service.ConsultorioService;
import br.com.mandalore.agendamentoconversa.application.domain.service.HabilitacaoAtendimento;

/**
 * Chegada, fila e desfazer chegada contra Postgres real (banco descartável com os schemas agenda, acesso e atendimento).
 * O relógio é fixo em 2030-01-07 10:00 (America/Sao_Paulo).
 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresAtendimentoAdapterIT {

    private static final String BASE = envOu("POSTGRES_JDBC_BASE_URL", "jdbc:postgresql://localhost:5432/");
    private static final String USUARIO = envOu("POSTGRES_USER", "postgres");
    private static final String SENHA = envOu("POSTGRES_PASSWORD", "postgres");
    private static final String CONTA = "it";
    private static final LocalDateTime AGORA = LocalDateTime.of(2030, 1, 7, 10, 0);

    private final String banco = "atendimento_adapter_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final AtomicInteger sequencia = new AtomicInteger();
    private JdbcTemplate jdbc;
    private PostgresAtendimentoAdapter adapter;
    private Clock relogio;

    @BeforeEach
    void criarBanco() throws Exception {
        executarNoAdmin("CREATE DATABASE " + banco);
        DataSource ds = new DriverManagerDataSource(BASE + banco, USUARIO, SENHA);
        jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/agenda/schema.sql"), new ClassPathResource("db/acesso/schema.sql"),
                new ClassPathResource("db/atendimento/schema.sql")).execute(ds);
        relogio = Clock.fixed(AGORA.atZone(PostgresAtendimentoAdapter.ZONA).toInstant(), PostgresAtendimentoAdapter.ZONA);
        adapter = new PostgresAtendimentoAdapter(new NamedParameterJdbcTemplate(jdbc), ds, relogio);
    }

    @AfterEach
    void removerBanco() throws Exception {
        if (jdbc != null) {
            executarNoAdmin("DROP DATABASE IF EXISTS " + banco + " WITH (FORCE)");
        }
    }

    @Test
    void chegadaCriaAtendimentoAguardandoEEventos() {
        long agendamento = novoAgendamento("MARCADO", "2030-01-07 09:00");

        Atendimento.Operacao op = adapter.registrarChegada(CONTA, agendamento, "recepcao");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(op.id()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT ie_status_atendimento FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?",
                String.class, op.id())).isEqualTo("AGUARDANDO");
        assertThat(jdbc.queryForObject("SELECT dt_entrada FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?",
                java.sql.Timestamp.class, op.id()).toLocalDateTime()).isEqualTo(AGORA);
        assertThat(statusAgendamento(agendamento)).isEqualTo("AGUARDANDO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.atendimento_evento WHERE nr_atendimento = ? AND tipo = 'CHEGADA'",
                Integer.class, op.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agenda.agendamento_evento WHERE agendamento_id = ? AND tipo = 'CHEGOU'",
                Integer.class, agendamento)).isEqualTo(1);
    }

    @Test
    void filaMarcaAChegadaFeitaPeloWhatsapp() {
        long pelaRecepcao = novoAgendamento("MARCADO", "2030-01-07 09:00");
        long peloWhatsapp = novoAgendamento("CONFIRMADO", "2030-01-07 09:30");
        adapter.registrarChegada(CONTA, pelaRecepcao, "recepcao");
        adapter.registrarChegada(CONTA, peloWhatsapp, Atendimento.CHEGADA_WHATSAPP);

        assertThat(adapter.fila(CONTA, AGORA.toLocalDate()))
                .filteredOn(f -> f.agendamentoId() == pelaRecepcao || f.agendamentoId() == peloWhatsapp)
                .extracting(Atendimento.FilaItem::agendamentoId, Atendimento.FilaItem::checkinWhatsapp)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(pelaRecepcao, false),
                        org.assertj.core.groups.Tuple.tuple(peloWhatsapp, true));
    }

    @Test
    void chegadaRepetidaDevolveOMesmoAtendimento() {
        long agendamento = novoAgendamento("MARCADO", "2030-01-07 09:00");

        Atendimento.Operacao primeira = adapter.registrarChegada(CONTA, agendamento, "recepcao");
        Atendimento.Operacao segunda = adapter.registrarChegada(CONTA, agendamento, "recepcao");

        assertThat(segunda.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(segunda.id()).isEqualTo(primeira.id());
        assertThat(totalAtendimentos(agendamento)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agenda.agendamento_evento WHERE agendamento_id = ? AND tipo = 'CHEGOU'",
                Integer.class, agendamento)).isEqualTo(1);
    }

    @Test
    void chegadaConcorrenteResultaEmUmUnicoAtendimento() throws Exception {
        long agendamento = novoAgendamento("CONFIRMADO", "2030-01-07 09:00");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Atendimento.Operacao>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return adapter.registrarChegada(CONTA, agendamento, "recepcao");
                }));
            }
            largada.countDown();
            List<Atendimento.Operacao> resultados = new ArrayList<>();
            for (Future<Atendimento.Operacao> futuro : futuros) {
                resultados.add(futuro.get(30, TimeUnit.SECONDS));
            }

            assertThat(resultados).allSatisfy(op -> assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO));
            assertThat(resultados.stream().map(Atendimento.Operacao::id).distinct()).hasSize(1);
            assertThat(totalAtendimentos(agendamento)).isEqualTo(1);
            assertThat(statusAgendamento(agendamento)).isEqualTo("AGUARDANDO");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void agendamentoDeOutroDiaERecusadoSemCriarAtendimento() {
        long agendamento = novoAgendamento("MARCADO", "2030-01-08 09:00");

        Atendimento.Operacao op = adapter.registrarChegada(CONTA, agendamento, "recepcao");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.CHEGADA_FORA_DO_DIA);
        assertThat(totalAtendimentos(agendamento)).isZero();
        assertThat(statusAgendamento(agendamento)).isEqualTo("MARCADO");
    }

    @Test
    void statusQueNaoAceitaChegadaERecusado() {
        for (String status : List.of("CANCELADO", "REMARCADO", "EM_CONSULTA", "EXECUTADO")) {
            long agendamento = novoAgendamento(status, "2030-01-07 09:00");

            Atendimento.Operacao op = adapter.registrarChegada(CONTA, agendamento, "recepcao");

            assertThat(op.codigo()).as(status).isEqualTo(CodigoNegocio.STATUS_INVALIDO_PARA_CHEGADA);
            assertThat(totalAtendimentos(agendamento)).as(status).isZero();
        }
    }

    @Test
    void agendamentoInexistenteOuDeOutraContaNaoEEncontrado() {
        long agendamento = novoAgendamento("MARCADO", "2030-01-07 09:00");

        assertThat(adapter.registrarChegada(CONTA, agendamento + 1000, "recepcao").codigo())
                .isEqualTo(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        assertThat(adapter.registrarChegada("outra", agendamento, "recepcao").codigo())
                .isEqualTo(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        assertThat(totalAtendimentos(agendamento)).isZero();
    }

    @Test
    void desfazerChegadaCancelaAtendimentoEVoltaParaMarcado() {
        long agendamento = novoAgendamento("MARCADO", "2030-01-07 09:00");
        long atendimento = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();

        Atendimento.Operacao op = adapter.desfazerChegada(CONTA, atendimento, "Paciente foi embora", "recepcao");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(statusAtendimento(atendimento)).isEqualTo("CANCELADO");
        assertThat(statusAgendamento(agendamento)).isEqualTo("MARCADO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.atendimento_evento WHERE nr_atendimento = ? "
                + "AND tipo = 'CHEGADA_DESFEITA'", Integer.class, atendimento)).isEqualTo(1);
    }

    @Test
    void desfazerChegadaVoltaParaConfirmadoQuandoHouveConfirmacao() {
        long agendamento = novoAgendamento("CONFIRMADO", "2030-01-07 09:00");
        jdbc.update("INSERT INTO agenda.agendamento_evento (agendamento_id, tipo, usuario, detalhe) VALUES (?, 'CONFIRMADO', 'it', '')",
                agendamento);
        long atendimento = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();

        Atendimento.Operacao op = adapter.desfazerChegada(CONTA, atendimento, "Engano", "recepcao");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(statusAgendamento(agendamento)).isEqualTo("CONFIRMADO");
    }

    @Test
    void desfazerChegadaComAtendimentoEmConsultaERecusado() {
        long agendamento = novoAgendamento("MARCADO", "2030-01-07 09:00");
        long atendimento = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        jdbc.update("UPDATE atendimento.atendimento_paciente SET ie_status_atendimento = 'EM_CONSULTA', "
                + "dt_atend_medico = CURRENT_TIMESTAMP WHERE nr_atendimento = ?", atendimento);

        Atendimento.Operacao op = adapter.desfazerChegada(CONTA, atendimento, "Engano", "recepcao");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_EM_ANDAMENTO);
        assertThat(statusAtendimento(atendimento)).isEqualTo("EM_CONSULTA");
        assertThat(statusAgendamento(agendamento)).isEqualTo("AGUARDANDO");
    }

    @Test
    void desfazerENovaChegadaGeraOutroAtendimento() {
        long agendamento = novoAgendamento("MARCADO", "2030-01-07 09:00");
        long primeiro = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        adapter.desfazerChegada(CONTA, primeiro, "Engano", "recepcao");

        Atendimento.Operacao nova = adapter.registrarChegada(CONTA, agendamento, "recepcao");

        assertThat(nova.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(nova.id()).isNotEqualTo(primeiro);
        assertThat(statusAtendimento(primeiro)).isEqualTo("CANCELADO");
        assertThat(statusAtendimento(nova.id())).isEqualTo("AGUARDANDO");
        assertThat(statusAgendamento(agendamento)).isEqualTo("AGUARDANDO");
    }

    @Test
    void filaMostraEsperaDoAguardandoSemCampoClinico() {
        long aguardando = novoAgendamento("MARCADO", "2030-01-07 09:00");
        long marcado = novoAgendamento("MARCADO", "2030-01-07 11:00");
        adapter.registrarChegada(CONTA, aguardando, "recepcao");
        // a chegada foi às 10:00 pelo relógio fixo; um adapter 25 minutos depois mede a espera
        PostgresAtendimentoAdapter depois = new PostgresAtendimentoAdapter(new NamedParameterJdbcTemplate(jdbc),
                jdbc.getDataSource(), Clock.fixed(Instant.from(relogio.instant().plusSeconds(25 * 60)), relogio.getZone()));

        List<Atendimento.FilaItem> fila = depois.fila(CONTA, LocalDate.of(2030, 1, 7));

        assertThat(fila).hasSize(2);
        Atendimento.FilaItem primeiro = fila.get(0);
        assertThat(primeiro.agendamentoId()).isEqualTo(aguardando);
        assertThat(primeiro.status()).isEqualTo("AGUARDANDO");
        assertThat(primeiro.atendimentoId()).isNotNull();
        assertThat(primeiro.esperaMin()).isEqualTo(25L);
        assertThat(primeiro.chegadaEm()).isEqualTo(AGORA);
        Atendimento.FilaItem segundo = fila.get(1);
        assertThat(segundo.agendamentoId()).isEqualTo(marcado);
        assertThat(segundo.atendimentoId()).isNull();
        assertThat(segundo.esperaMin()).isNull();
        assertThat(primeiro.chamadoEm()).isNull();
        // o tipo FilaItem só expõe identificação, horário, status, espera, chamada e a origem da chegada
        assertThat(Atendimento.FilaItem.class.getRecordComponents()).extracting("name").containsExactly("agendamentoId",
                "atendimentoId", "horario", "paciente", "cpf", "profissional", "especialidade", "status", "chegadaEm",
                "esperaMin", "chamadoEm", "localChamada", "checkinWhatsapp");
    }

    @Test
    void profissionalDoUsuarioResolveSoVinculoAtivoDaConta() {
        long usuario = novoUsuario();
        long outroUsuario = novoUsuario();
        long semVinculo = novoUsuario();
        long profissional = novoProfissional(CONTA, usuario, true);
        novoProfissional("outra", outroUsuario, true);

        assertThat(adapter.profissionalDoUsuario(CONTA, usuario)).hasValueSatisfying(p -> {
            assertThat(p.id()).isEqualTo(profissional);
            assertThat(p.conselhoNumero()).isEqualTo("12345");
            assertThat(p.conselhoUf()).isEqualTo("SP");
        });
        assertThat(adapter.profissionalDoUsuario(CONTA, semVinculo)).isEmpty();
        assertThat(adapter.profissionalDoUsuario(CONTA, outroUsuario)).isEmpty();
        assertThat(adapter.profissionalDoUsuario("outra", usuario)).isEmpty();
    }

    @Test
    void atendimentoPorContaDevolveFichaSoDaPropriaConta() {
        long agendamento = novoAgendamento("MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();

        assertThat(adapter.atendimento(CONTA, nr)).hasValueSatisfying(f -> {
            assertThat(f.agendamentoId()).isEqualTo(agendamento);
            assertThat(f.status()).isEqualTo(Atendimento.Status.AGUARDANDO);
        });
        assertThat(adapter.atendimento("outra", nr)).isEmpty();
        assertThat(adapter.atendimento(CONTA, nr + 1000)).isEmpty();
    }

    @Test
    void filaDoProfissionalOrdenaPorChegadaEMostraEsperaSoDoAguardando() {
        long medico = novoProfissional(CONTA, null, true);
        long a1 = novoAgendamento(medico, "MARCADO", "2030-01-07 09:00");
        long a2 = novoAgendamento(medico, "MARCADO", "2030-01-07 09:30");
        long emConsulta = novoAgendamento(medico, "MARCADO", "2030-01-07 08:00");
        long finalizado = novoAgendamento(medico, "MARCADO", "2030-01-07 07:00");
        long semChegada = novoAgendamento(medico, "MARCADO", "2030-01-07 11:00");
        long outroDia = novoAgendamento(medico, "MARCADO", "2030-01-08 09:00");
        long desfeito = novoAgendamento(medico, "MARCADO", "2030-01-07 12:00");
        long deOutroMedico = novoAgendamento("MARCADO", "2030-01-07 09:00");

        long n1 = adapter.registrarChegada(CONTA, a1, "recepcao").id();
        long n2 = adapter.registrarChegada(CONTA, a2, "recepcao").id();
        long nConsulta = adapter.registrarChegada(CONTA, emConsulta, "recepcao").id();
        long nFinal = adapter.registrarChegada(CONTA, finalizado, "recepcao").id();
        adapter.registrarChegada(CONTA, deOutroMedico, "recepcao");
        adapter.desfazerChegada(CONTA, adapter.registrarChegada(CONTA, desfeito, "recepcao").id(), "Engano", "recepcao");
        // a chegada de a2 foi antes da de a1
        definirEntrada(n1, "2030-01-07 09:50");
        definirEntrada(n2, "2030-01-07 09:40");
        definirEntrada(nConsulta, "2030-01-07 08:00");
        definirEntrada(nFinal, "2030-01-07 07:00");
        jdbc.update("UPDATE atendimento.atendimento_paciente SET ie_status_atendimento = 'EM_CONSULTA', "
                + "dt_atend_medico = CURRENT_TIMESTAMP WHERE nr_atendimento = ?", nConsulta);
        jdbc.update("UPDATE atendimento.atendimento_paciente SET ie_status_atendimento = 'FINALIZADO', "
                + "dt_alta = CURRENT_TIMESTAMP, ie_motivo_alta = 'ALTA_MEDICA' WHERE nr_atendimento = ?", nFinal);

        List<Atendimento.FilaItem> fila = adapter.filaDoProfissional(CONTA, medico, LocalDate.of(2030, 1, 7));

        assertThat(fila).extracting(Atendimento.FilaItem::atendimentoId).containsExactly(n2, n1, nConsulta, nFinal);
        assertThat(fila).extracting(Atendimento.FilaItem::agendamentoId)
                .doesNotContain(semChegada, outroDia, desfeito, deOutroMedico);
        // relógio fixo 10:00: n2 aguarda 20 min, n1 aguarda 10 min; os demais não têm espera
        assertThat(fila).extracting(Atendimento.FilaItem::esperaMin).containsExactly(20L, 10L, null, null);
        assertThat(adapter.filaDoProfissional("outra", medico, LocalDate.of(2030, 1, 7))).isEmpty();
    }

    // --- iniciar consulta (6.3) ---

    @Test
    void iniciarConsultaValidoAtualizaAtendimentoAgendamentoEEventos() {
        long medico = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(medico, "MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();

        Atendimento.Operacao op = adapter.iniciarConsulta(CONTA, nr, medico, "dr.silva");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(op.id()).isEqualTo(nr);
        assertThat(statusAtendimento(nr)).isEqualTo("EM_CONSULTA");
        assertThat(jdbc.queryForObject("SELECT dt_atend_medico FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?",
                java.sql.Timestamp.class, nr).toLocalDateTime()).isEqualTo(AGORA);
        assertThat(jdbc.queryForObject("SELECT nm_usuario_atend FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?",
                String.class, nr)).isEqualTo("dr.silva");
        assertThat(statusAgendamento(agendamento)).isEqualTo("EM_CONSULTA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.atendimento_evento WHERE nr_atendimento = ? "
                + "AND tipo = 'CONSULTA_INICIADA'", Integer.class, nr)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agenda.agendamento_evento WHERE agendamento_id = ? "
                + "AND tipo = 'EM_CONSULTA'", Integer.class, agendamento)).isEqualTo(1);
    }

    @Test
    void iniciarConsultaDeOutroProfissionalNaoEncontradoESemAlteracao() {
        long medico = novoProfissional(CONTA, null, true);
        long outro = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(medico, "MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();

        Atendimento.Operacao op = adapter.iniciarConsulta(CONTA, nr, outro, "dr.outro");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(statusAtendimento(nr)).isEqualTo("AGUARDANDO");
        assertThat(statusAgendamento(agendamento)).isEqualTo("AGUARDANDO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.atendimento_evento WHERE nr_atendimento = ? "
                + "AND tipo = 'CONSULTA_INICIADA'", Integer.class, nr)).isZero();
    }

    @Test
    void iniciarConsultaComStatusInvalidoERecusado() {
        long medico = novoProfissional(CONTA, null, true);
        long emConsulta = novoAgendamento(medico, "MARCADO", "2030-01-07 09:00");
        long cancelado = novoAgendamento(medico, "MARCADO", "2030-01-07 10:00");
        long nrConsulta = adapter.registrarChegada(CONTA, emConsulta, "recepcao").id();
        long nrCancelado = adapter.registrarChegada(CONTA, cancelado, "recepcao").id();
        assertThat(adapter.iniciarConsulta(CONTA, nrConsulta, medico, "dr").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        adapter.desfazerChegada(CONTA, nrCancelado, "Engano", "recepcao");

        assertThat(adapter.iniciarConsulta(CONTA, nrConsulta, medico, "dr").codigo())
                .isEqualTo(CodigoNegocio.STATUS_INVALIDO_PARA_CONSULTA);
        assertThat(adapter.iniciarConsulta(CONTA, nrCancelado, medico, "dr").codigo())
                .isEqualTo(CodigoNegocio.STATUS_INVALIDO_PARA_CONSULTA);
        assertThat(statusAtendimento(nrConsulta)).isEqualTo("EM_CONSULTA");
        assertThat(statusAtendimento(nrCancelado)).isEqualTo("CANCELADO");
    }

    @Test
    void iniciarConsultaComProfissionalJaEmConsultaNaoAlteraOSegundo() {
        long medico = novoProfissional(CONTA, null, true);
        long ag1 = novoAgendamento(medico, "MARCADO", "2030-01-07 09:00");
        long ag2 = novoAgendamento(medico, "MARCADO", "2030-01-07 10:00");
        long nr1 = adapter.registrarChegada(CONTA, ag1, "recepcao").id();
        long nr2 = adapter.registrarChegada(CONTA, ag2, "recepcao").id();
        assertThat(adapter.iniciarConsulta(CONTA, nr1, medico, "dr").codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        Atendimento.Operacao op = adapter.iniciarConsulta(CONTA, nr2, medico, "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.PROFISSIONAL_EM_CONSULTA);
        assertThat(statusAtendimento(nr2)).isEqualTo("AGUARDANDO");
        assertThat(statusAgendamento(ag2)).isEqualTo("AGUARDANDO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.atendimento_evento WHERE nr_atendimento = ? "
                + "AND tipo = 'CONSULTA_INICIADA'", Integer.class, nr2)).isZero();
        assertThat(statusAtendimento(nr1)).isEqualTo("EM_CONSULTA");
    }

    @Test
    void usuarioVinculadoAOutroProfissionalRecebeAcessoNegadoAoIniciar() {
        long usuarioDono = novoUsuario();
        long usuarioOutro = novoUsuario();
        long dono = novoProfissional(CONTA, usuarioDono, true);
        novoProfissional(CONTA, usuarioOutro, true);
        long agendamento = novoAgendamento(dono, "MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("origem", (OrigemAgendaPort) conta -> true);
        HabilitacaoAtendimento habilitacao = new HabilitacaoAtendimento(beans.getBeanProvider(OrigemAgendaPort.class), true);
        ConsultorioService service = new ConsultorioService(adapter, habilitacao, 30);

        assertThatThrownBy(() -> service.iniciarConsulta(new ConsultorioUseCase.Contexto(CONTA, usuarioOutro, "outro"), nr))
                .isInstanceOf(AcessoNegadoException.class);

        assertThat(statusAtendimento(nr)).isEqualTo("AGUARDANDO");
        assertThat(statusAgendamento(agendamento)).isEqualTo("AGUARDANDO");
        assertThat(service.iniciarConsulta(new ConsultorioUseCase.Contexto(CONTA, usuarioDono, "dono"), nr).codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
    }

    // --- anamnese (7.2) ---

    @Test
    void salvarRascunhoAnamneseCriaEAtualizaOUnicoRegistroAtivo() {
        Consulta consulta = novaConsultaEmAndamento();

        Atendimento.Operacao criado = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(),
                consulta.profissionalId(), anamnese(consulta, "Dor abdominal", "Há dois dias"), "dr.criador");
        Atendimento.Operacao atualizado = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(),
                consulta.profissionalId(), anamnese(consulta, "Dor abdominal intensa", "Há três dias"), "dr.editor");

        assertThat(criado.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(atualizado.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(atualizado.id()).isEqualTo(criado.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.anamnese_paciente "
                + "WHERE nr_atendimento = ? AND ie_situacao = 'A'", Integer.class, consulta.nrAtendimento())).isEqualTo(1);
        assertThat(adapter.anamnese(CONTA, criado.id())).hasValueSatisfying(a -> {
            assertThat(a.queixaPrincipal()).isEqualTo("Dor abdominal intensa");
            assertThat(a.historiaDoencaAtual()).isEqualTo("Há três dias");
            assertThat(a.usuario()).isEqualTo("dr.editor");
            assertThat(a.liberada()).isFalse();
        });
    }

    @Test
    void liberarAnamneseSemQueixaPrincipalERecusado() {
        Consulta consulta = novaConsultaEmAndamento();
        long anamnese = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, "   ", "História preenchida"), "dr").id();

        assertThatThrownBy(() -> adapter.liberarAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(), "dr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queixa principal");

        assertThat(adapter.anamnese(CONTA, anamnese)).hasValueSatisfying(a -> assertThat(a.liberada()).isFalse());
    }

    @Test
    void salvarRascunhoSobreAnamneseLiberadaERecusadoSemAlterarConteudo() {
        Consulta consulta = novaConsultaEmAndamento();
        long anamnese = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, "Dor original", "História original"), "dr").id();
        assertThat(adapter.liberarAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(), "dr").codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);

        Atendimento.Operacao op = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(),
                consulta.profissionalId(), anamnese(consulta, "Queixa alterada", "História alterada"), "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.REGISTRO_JA_LIBERADO);
        assertThat(adapter.anamnese(CONTA, anamnese)).hasValueSatisfying(a -> {
            assertThat(a.queixaPrincipal()).isEqualTo("Dor original");
            assertThat(a.historiaDoencaAtual()).isEqualTo("História original");
            assertThat(a.liberada()).isTrue();
        });
    }

    @Test
    void salvarAnamneseForaDeEmConsultaERecusado() {
        long profissional = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(profissional, "MARCADO", "2030-01-07 09:00");
        long atendimento = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        Consulta consulta = new Consulta(profissional, atendimento);

        Atendimento.Operacao op = adapter.salvarRascunhoAnamnese(CONTA, atendimento, profissional,
                anamnese(consulta, "Dor", "História"), "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.anamnese_paciente WHERE nr_atendimento = ?",
                Integer.class, atendimento)).isZero();
    }

    @Test
    void liberarAnamneseForaDeEmConsultaERecusado() {
        Consulta consulta = novaConsultaEmAndamento();
        long anamnese = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, "Dor", "História"), "dr").id();
        voltarParaAguardando(consulta.nrAtendimento());

        Atendimento.Operacao op = adapter.liberarAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(), "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.anamnese(CONTA, anamnese)).hasValueSatisfying(a -> assertThat(a.liberada()).isFalse());
    }

    @Test
    void inativarAnamneseForaDeEmConsultaERecusado() {
        Consulta consulta = novaConsultaEmAndamento();
        long anamnese = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, "Dor", "História"), "dr").id();
        adapter.liberarAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(), "dr");
        voltarParaAguardando(consulta.nrAtendimento());

        Atendimento.Operacao op = adapter.inativarAnamnese(CONTA, anamnese, consulta.profissionalId(), "Correção", "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.anamnese(CONTA, anamnese)).hasValueSatisfying(a -> assertThat(a.ativa()).isTrue());
    }

    @Test
    void inativarAnamneseLiberadaComJustificativaPermiteNovaAnamneseAtiva() {
        Consulta consulta = novaConsultaEmAndamento();
        long anterior = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, "Dor", "História"), "dr").id();
        adapter.liberarAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(), "dr");

        Atendimento.Operacao inativada = adapter.inativarAnamnese(CONTA, anterior, consulta.profissionalId(),
                "Informação registrada incorretamente", "dr");
        Atendimento.Operacao nova = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(),
                consulta.profissionalId(), anamnese(consulta, "Queixa corrigida", "Nova história"), "dr");

        assertThat(inativada.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(nova.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(nova.id()).isNotEqualTo(anterior);
        assertThat(adapter.anamnese(CONTA, anterior)).hasValueSatisfying(a -> {
            assertThat(a.ativa()).isFalse();
            assertThat(a.justificativa()).isEqualTo("Informação registrada incorretamente");
            assertThat(a.dtInativacao()).isEqualTo(AGORA);
        });
        assertThat(adapter.prontuario(CONTA, consulta.nrAtendimento())).hasValueSatisfying(p -> {
            assertThat(p.anamnese().id()).isEqualTo(nova.id());
            assertThat(p.anamnese().queixaPrincipal()).isEqualTo("Queixa corrigida");
            assertThat(p.anamnese().ativa()).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.anamnese_paciente "
                + "WHERE nr_atendimento = ? AND ie_situacao = 'A'", Integer.class, consulta.nrAtendimento())).isEqualTo(1);
    }

    @Test
    void operacoesDeAnamneseNaoAcessamRegistroDeOutraConta() {
        Consulta consulta = novaConsultaEmAndamento();
        long anamnese = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, "Dor", "História"), "dr").id();

        assertThat(adapter.salvarRascunhoAnamnese("outra", consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, "Alterada", "Alterada"), "dr").codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.liberarAnamnese("outra", consulta.nrAtendimento(), consulta.profissionalId(), "dr").codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.inativarAnamnese("outra", anamnese, consulta.profissionalId(), "Correção", "dr").codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.anamnese("outra", anamnese)).isEmpty();
        assertThat(adapter.anamnese(CONTA, anamnese)).hasValueSatisfying(a -> {
            assertThat(a.queixaPrincipal()).isEqualTo("Dor");
            assertThat(a.ativa()).isTrue();
            assertThat(a.liberada()).isFalse();
        });
    }

    @Test
    void operacoesDeAnamneseNaoAcessamRegistroDeOutroProfissional() {
        Consulta consulta = novaConsultaEmAndamento();
        long outroProfissional = novoProfissional(CONTA, null, true);
        long anamnese = adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, "Dor", "História"), "dr").id();

        assertThat(adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), outroProfissional,
                anamnese(consulta, "Alterada", "Alterada"), "outro").codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.liberarAnamnese(CONTA, consulta.nrAtendimento(), outroProfissional, "outro").codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.inativarAnamnese(CONTA, anamnese, outroProfissional, "Correção", "outro").codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.anamnese(CONTA, anamnese)).hasValueSatisfying(a -> {
            assertThat(a.profissionalId()).isEqualTo(consulta.profissionalId());
            assertThat(a.queixaPrincipal()).isEqualTo("Dor");
            assertThat(a.ativa()).isTrue();
            assertThat(a.liberada()).isFalse();
        });
    }

    // --- evolução (8.2) ---

    @Test
    void variasEvolucoesPorAtendimentoFicamAtivasEmOrdemNoProntuario() {
        Consulta consulta = novaConsultaEmAndamento();

        long primeira = criarEvolucao(consulta, "Primeira evolução").id();
        long segunda = criarEvolucao(consulta, "Segunda evolução").id();

        assertThat(segunda).isNotEqualTo(primeira);
        assertThat(adapter.prontuario(CONTA, consulta.nrAtendimento())).hasValueSatisfying(p -> {
            assertThat(p.evolucoes()).extracting(Atendimento.Evolucao::id).containsExactly(primeira, segunda);
            assertThat(p.evolucoes()).extracting(Atendimento.Evolucao::texto)
                    .containsExactly("Primeira evolução", "Segunda evolução");
            assertThat(p.evolucoes()).allSatisfy(e -> {
                assertThat(e.ativa()).isTrue();
                assertThat(e.liberada()).isFalse();
            });
        });
    }

    @Test
    void editarRascunhoDeEvolucaoAlteraTextoPesoAlturaERetorno() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarEvolucao(consulta, "Texto inicial").id();

        Atendimento.Operacao op = adapter.editarEvolucao(CONTA, id, consulta.profissionalId(),
                evolucao(consulta, "Texto revisado", new java.math.BigDecimal("72.50"), new java.math.BigDecimal("171.0"),
                        LocalDate.of(2030, 2, 15)), "dr.editor");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.evolucao(CONTA, id)).hasValueSatisfying(e -> {
            assertThat(e.texto()).isEqualTo("Texto revisado");
            assertThat(e.pesoKg()).isEqualByComparingTo("72.50");
            assertThat(e.alturaCm()).isEqualByComparingTo("171.0");
            assertThat(e.retornoPrevisto()).isEqualTo(LocalDate.of(2030, 2, 15));
            assertThat(e.usuario()).isEqualTo("dr.editor");
            assertThat(e.liberada()).isFalse();
        });
    }

    @Test
    void liberarEvolucaoComTextoVazioLancaExcecaoENaoLibera() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarEvolucao(consulta, "   ").id();

        assertThatThrownBy(() -> adapter.liberarEvolucao(CONTA, id, consulta.profissionalId(), "dr"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(adapter.evolucao(CONTA, id)).hasValueSatisfying(e -> assertThat(e.liberada()).isFalse());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.atendimento_evento WHERE nr_atendimento = ? "
                + "AND tipo = 'EVOLUCAO_LIBERADA'", Integer.class, consulta.nrAtendimento())).isZero();
    }

    @Test
    void editarEvolucaoLiberadaDevolveRegistroJaLiberadoSemAlterarConteudo() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarEvolucao(consulta, "Texto original").id();
        assertThat(adapter.liberarEvolucao(CONTA, id, consulta.profissionalId(), "dr").codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);

        Atendimento.Operacao op = adapter.editarEvolucao(CONTA, id, consulta.profissionalId(),
                evolucao(consulta, "Texto alterado", new java.math.BigDecimal("80"), null, null), "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.REGISTRO_JA_LIBERADO);
        assertThat(adapter.evolucao(CONTA, id)).hasValueSatisfying(e -> {
            assertThat(e.texto()).isEqualTo("Texto original");
            assertThat(e.pesoKg()).isNull();
            assertThat(e.liberada()).isTrue();
        });
    }

    @Test
    void inativarEvolucaoSemJustificativaLancaExcecaoEMantemAtiva() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarEvolucao(consulta, "Texto").id();
        adapter.liberarEvolucao(CONTA, id, consulta.profissionalId(), "dr");

        for (String justificativa : new String[] {"", "   ", null}) {
            assertThatThrownBy(() -> adapter.inativarEvolucao(CONTA, id, consulta.profissionalId(), justificativa, "dr"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(adapter.evolucao(CONTA, id)).hasValueSatisfying(e -> assertThat(e.ativa()).isTrue());
    }

    @Test
    void inativarEvolucaoRascunhoDevolveRegistroNaoLiberado() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarEvolucao(consulta, "Rascunho").id();

        Atendimento.Operacao op = adapter.inativarEvolucao(CONTA, id, consulta.profissionalId(), "Motivo", "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.REGISTRO_NAO_LIBERADO);
        assertThat(adapter.evolucao(CONTA, id)).hasValueSatisfying(e -> assertThat(e.ativa()).isTrue());
    }

    @Test
    void inativarEvolucaoLiberadaGravaSituacaoDataEJustificativaESomeDoProntuario() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarEvolucao(consulta, "Texto").id();
        long mantida = criarEvolucao(consulta, "Outra").id();
        adapter.liberarEvolucao(CONTA, id, consulta.profissionalId(), "dr");

        Atendimento.Operacao op = adapter.inativarEvolucao(CONTA, id, consulta.profissionalId(), "  Registro duplicado  ",
                "dr.inativa");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(jdbc.queryForObject("SELECT ie_situacao FROM atendimento.evolucao_paciente WHERE cd_evolucao = ?",
                String.class, id)).isEqualTo("I");
        assertThat(adapter.evolucao(CONTA, id)).hasValueSatisfying(e -> {
            assertThat(e.ativa()).isFalse();
            assertThat(e.dtInativacao()).isEqualTo(AGORA);
            assertThat(e.justificativa()).isEqualTo("Registro duplicado");
            assertThat(e.usuarioInativacao()).isEqualTo("dr.inativa");
        });
        assertThat(adapter.prontuario(CONTA, consulta.nrAtendimento())).hasValueSatisfying(p ->
                assertThat(p.evolucoes()).extracting(Atendimento.Evolucao::id).containsExactly(mantida));
    }

    @Test
    void escritaDeEvolucaoForaDeEmConsultaDevolveAtendimentoNaoEmConsulta() {
        Consulta consulta = novaConsultaEmAndamento();
        long rascunho = criarEvolucao(consulta, "Rascunho").id();
        long liberada = criarEvolucao(consulta, "Liberada").id();
        adapter.liberarEvolucao(CONTA, liberada, consulta.profissionalId(), "dr");
        voltarParaAguardando(consulta.nrAtendimento());

        assertThat(criarEvolucao(consulta, "Nova").codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.editarEvolucao(CONTA, rascunho, consulta.profissionalId(),
                evolucao(consulta, "Alterado", null, null, null), "dr").codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.liberarEvolucao(CONTA, rascunho, consulta.profissionalId(), "dr").codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.inativarEvolucao(CONTA, liberada, consulta.profissionalId(), "Motivo", "dr").codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.evolucao_paciente WHERE nr_atendimento = ?",
                Integer.class, consulta.nrAtendimento())).isEqualTo(2);
        assertThat(adapter.evolucao(CONTA, rascunho)).hasValueSatisfying(e -> {
            assertThat(e.texto()).isEqualTo("Rascunho");
            assertThat(e.liberada()).isFalse();
        });
        assertThat(adapter.evolucao(CONTA, liberada)).hasValueSatisfying(e -> assertThat(e.ativa()).isTrue());
    }

    @Test
    void operacoesDeEvolucaoNaoAcessamRegistroDeOutraContaNemDeOutroProfissional() {
        Consulta consulta = novaConsultaEmAndamento();
        long outroProfissional = novoProfissional(CONTA, null, true);
        long rascunho = criarEvolucao(consulta, "Rascunho").id();
        long liberada = criarEvolucao(consulta, "Liberada").id();
        adapter.liberarEvolucao(CONTA, liberada, consulta.profissionalId(), "dr");

        assertThat(adapter.criarEvolucao("outra", consulta.nrAtendimento(), consulta.profissionalId(),
                evolucao(consulta, "X", null, null, null), "dr").codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.criarEvolucao(CONTA, consulta.nrAtendimento(), outroProfissional,
                evolucao(consulta, "X", null, null, null), "outro").codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        for (String[] contaEProfissional : new String[][] {{"outra", "0"}, {CONTA, "1"}}) {
            String conta = contaEProfissional[0];
            long profissional = "1".equals(contaEProfissional[1]) ? outroProfissional : consulta.profissionalId();
            assertThat(adapter.editarEvolucao(conta, rascunho, profissional, evolucao(consulta, "Alterado", null, null, null),
                    "x").codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
            assertThat(adapter.liberarEvolucao(conta, rascunho, profissional, "x").codigo())
                    .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
            assertThat(adapter.inativarEvolucao(conta, liberada, profissional, "Motivo", "x").codigo())
                    .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        }

        assertThat(adapter.evolucao("outra", rascunho)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.evolucao_paciente WHERE nr_atendimento = ?",
                Integer.class, consulta.nrAtendimento())).isEqualTo(2);
        assertThat(adapter.evolucao(CONTA, rascunho)).hasValueSatisfying(e -> {
            assertThat(e.texto()).isEqualTo("Rascunho");
            assertThat(e.liberada()).isFalse();
        });
        assertThat(adapter.evolucao(CONTA, liberada)).hasValueSatisfying(e -> {
            assertThat(e.ativa()).isTrue();
            assertThat(e.liberada()).isTrue();
        });
    }

    @Test
    void eventosDeEvolucaoNaoContemTextoClinico() {
        Consulta consulta = novaConsultaEmAndamento();
        String sigiloso = "Paciente relata dor torácica SIGILOSO";
        long id = criarEvolucao(consulta, sigiloso).id();
        adapter.editarEvolucao(CONTA, id, consulta.profissionalId(), evolucao(consulta, sigiloso + " revisado", null, null, null), "dr");
        adapter.liberarEvolucao(CONTA, id, consulta.profissionalId(), "dr");
        adapter.inativarEvolucao(CONTA, id, consulta.profissionalId(), "Motivo", "dr");

        List<String> tipos = jdbc.queryForList("SELECT tipo FROM atendimento.atendimento_evento WHERE nr_atendimento = ? "
                + "AND tipo LIKE 'EVOLUCAO_%'", String.class, consulta.nrAtendimento());
        List<String> detalhes = jdbc.queryForList("SELECT usuario || ' ' || detalhe FROM atendimento.atendimento_evento "
                + "WHERE nr_atendimento = ? AND tipo LIKE 'EVOLUCAO_%'", String.class, consulta.nrAtendimento());

        assertThat(tipos).contains("EVOLUCAO_SALVA", "EVOLUCAO_LIBERADA", "EVOLUCAO_INATIVADA");
        assertThat(detalhes).isNotEmpty().allSatisfy(d -> assertThat(d).doesNotContain("SIGILOSO").doesNotContain("torácica"));
    }

    // --- diagnóstico (9.2) ---

    @Test
    void incluirDiagnosticosPrincipalESecundarioAparecemNoProntuarioComPrincipalPrimeiro() {
        Consulta consulta = novaConsultaEmAndamento();

        long secundario = incluirDiagnostico(consulta, "E11", Atendimento.TipoDiagnostico.SECUNDARIO).id();
        long principal = incluirDiagnostico(consulta, "J06.9", Atendimento.TipoDiagnostico.PRINCIPAL).id();

        assertThat(adapter.prontuario(CONTA, consulta.nrAtendimento())).hasValueSatisfying(p ->
                assertThat(p.diagnosticos()).extracting(Atendimento.Diagnostico::id).containsExactly(principal, secundario));
    }

    @Test
    void segundoDiagnosticoPrincipalAtivoDevolveCodigoEInativarLiberaONovo() {
        Consulta consulta = novaConsultaEmAndamento();
        long primeiro = incluirDiagnostico(consulta, "J06", Atendimento.TipoDiagnostico.PRINCIPAL).id();

        assertThat(incluirDiagnostico(consulta, "J20", Atendimento.TipoDiagnostico.PRINCIPAL).codigo())
                .isEqualTo(CodigoNegocio.DIAGNOSTICO_PRINCIPAL_JA_EXISTE);
        assertThat(adapter.inativarDiagnostico(CONTA, primeiro, consulta.profissionalId(), "CID errado", "dr").codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(incluirDiagnostico(consulta, "J20", Atendimento.TipoDiagnostico.PRINCIPAL).codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.diagnostico(CONTA, primeiro)).hasValueSatisfying(d -> {
            assertThat(d.ativo()).isFalse();
            assertThat(d.justificativa()).isEqualTo("CID errado");
        });
    }

    @Test
    void cidRepetidoAtivoDevolveDiagnosticoDuplicado() {
        Consulta consulta = novaConsultaEmAndamento();
        incluirDiagnostico(consulta, "J06", Atendimento.TipoDiagnostico.SECUNDARIO);

        assertThat(incluirDiagnostico(consulta, "J06", Atendimento.TipoDiagnostico.SECUNDARIO).codigo())
                .isEqualTo(CodigoNegocio.DIAGNOSTICO_DUPLICADO);
    }

    @Test
    void cidComFormatoInvalidoERecusadoPeloBanco() {
        Consulta consulta = novaConsultaEmAndamento();

        for (String cid : List.of("j06", "J6", "J060", "06J", "J06.", "J06.123")) {
            assertThatThrownBy(() -> incluirDiagnostico(consulta, cid, Atendimento.TipoDiagnostico.SECUNDARIO))
                    .as(cid).isInstanceOf(IllegalArgumentException.class);
        }
        for (String cid : List.of("A00", "J06.9", "Z00.0A")) {
            assertThat(incluirDiagnostico(consulta, cid, Atendimento.TipoDiagnostico.SECUNDARIO).codigo())
                    .as(cid).isEqualTo(CodigoNegocio.SUCESSO);
        }
    }

    @Test
    void diagnosticoForaDeEmConsultaOuDeOutraContaERecusado() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = incluirDiagnostico(consulta, "J06", Atendimento.TipoDiagnostico.PRINCIPAL).id();

        assertThat(adapter.inativarDiagnostico("outra", id, consulta.profissionalId(), "Motivo", "dr").codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        voltarParaAguardando(consulta.nrAtendimento());
        assertThat(incluirDiagnostico(consulta, "E11", Atendimento.TipoDiagnostico.SECUNDARIO).codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.inativarDiagnostico(CONTA, id, consulta.profissionalId(), "Motivo", "dr").codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.diagnostico(CONTA, id)).hasValueSatisfying(d -> assertThat(d.ativo()).isTrue());
    }

    // --- receita (10.4) ---

    @Test
    void criarEditarELiberarReceitaGravaItensEmOrdemEValidade() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarReceita(consulta, item("Dipirona", "1 cp de 6/6h")).id();

        Atendimento.Operacao editada = adapter.editarReceita(CONTA, id, consulta.profissionalId(),
                receita(consulta, "Tomar com água", item("Amoxicilina", "1 cp de 8/8h"), item("Dipirona", "Se dor")), "dr");
        Atendimento.Operacao liberada = adapter.liberarReceita(CONTA, id, consulta.profissionalId(),
                LocalDate.of(2030, 2, 6), "dr");

        assertThat(editada.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(liberada.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.receita(CONTA, id)).hasValueSatisfying(r -> {
            assertThat(r.observacao()).isEqualTo("Tomar com água");
            assertThat(r.itens()).extracting(Atendimento.ReceitaItem::ordem).containsExactly(1, 2);
            assertThat(r.itens()).extracting(Atendimento.ReceitaItem::medicamento).containsExactly("Amoxicilina", "Dipirona");
            assertThat(r.dtLiberacao()).isEqualTo(AGORA);
            assertThat(r.dtValidade()).isEqualTo(LocalDate.of(2030, 2, 6));
        });
        assertThat(adapter.prontuario(CONTA, consulta.nrAtendimento())).hasValueSatisfying(p ->
                assertThat(p.receitas()).extracting(Atendimento.Receita::id).containsExactly(id));
    }

    @Test
    void liberarReceitaSemItensOuSemPosologiaLancaExcecaoENaoLibera() {
        Consulta consulta = novaConsultaEmAndamento();
        long semItens = criarReceita(consulta).id();
        long semPosologia = criarReceita(consulta, item("Dipirona", "  ")).id();

        for (long id : new long[] {semItens, semPosologia}) {
            assertThatThrownBy(() -> adapter.liberarReceita(CONTA, id, consulta.profissionalId(), LocalDate.of(2030, 2, 6), "dr"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(adapter.receita(CONTA, id)).hasValueSatisfying(r -> assertThat(r.liberada()).isFalse());
        }
    }

    @Test
    void editarReceitaLiberadaDevolveRegistroJaLiberadoSemAlterarItens() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarReceita(consulta, item("Dipirona", "1 cp de 6/6h")).id();
        adapter.liberarReceita(CONTA, id, consulta.profissionalId(), LocalDate.of(2030, 2, 6), "dr");

        Atendimento.Operacao op = adapter.editarReceita(CONTA, id, consulta.profissionalId(),
                receita(consulta, "", item("Outro", "Outra")), "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.REGISTRO_JA_LIBERADO);
        assertThat(adapter.receita(CONTA, id)).hasValueSatisfying(r ->
                assertThat(r.itens()).extracting(Atendimento.ReceitaItem::medicamento).containsExactly("Dipirona"));
    }

    @Test
    void cancelarReceitaExigeLiberacaoEJustificativaEMantemORegistro() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarReceita(consulta, item("Dipirona", "1 cp de 6/6h")).id();

        assertThat(adapter.cancelarReceita(CONTA, id, consulta.profissionalId(), "Motivo", "dr").codigo())
                .isEqualTo(CodigoNegocio.REGISTRO_NAO_LIBERADO);
        adapter.liberarReceita(CONTA, id, consulta.profissionalId(), LocalDate.of(2030, 2, 6), "dr");
        assertThatThrownBy(() -> adapter.cancelarReceita(CONTA, id, consulta.profissionalId(), " ", "dr"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(adapter.cancelarReceita(CONTA, id, consulta.profissionalId(), "Dose errada", "dr").codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.receita(CONTA, id)).hasValueSatisfying(r -> {
            assertThat(r.ativa()).isFalse();
            assertThat(r.justificativa()).isEqualTo("Dose errada");
            assertThat(r.itens()).hasSize(1);
        });
        assertThat(adapter.prontuario(CONTA, consulta.nrAtendimento())).hasValueSatisfying(p ->
                assertThat(p.receitas()).isEmpty());
    }

    @Test
    void operacoesDeReceitaNaoAcessamOutraContaNemOutroProfissional() {
        Consulta consulta = novaConsultaEmAndamento();
        long outroProfissional = novoProfissional(CONTA, null, true);
        long id = criarReceita(consulta, item("Dipirona", "1 cp de 6/6h")).id();

        assertThat(adapter.receita("outra", id)).isEmpty();
        assertThat(adapter.cabecalhoReceita("outra", id)).isEmpty();
        assertThat(adapter.liberarReceita("outra", id, consulta.profissionalId(), LocalDate.of(2030, 2, 6), "dr").codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.liberarReceita(CONTA, id, outroProfissional, LocalDate.of(2030, 2, 6), "dr").codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.criarReceita(CONTA, consulta.nrAtendimento(), outroProfissional,
                receita(consulta, "", item("X", "Y")), "dr").codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.receita(CONTA, id)).hasValueSatisfying(r -> assertThat(r.liberada()).isFalse());
    }

    @Test
    void cabecalhoDaReceitaTrazClinicaEPaciente() {
        Consulta consulta = novaConsultaEmAndamento();
        long id = criarReceita(consulta, item("Dipirona", "1 cp")).id();
        PostgresAtendimentoAdapter comNome = new PostgresAtendimentoAdapter(new NamedParameterJdbcTemplate(jdbc),
                jdbc.getDataSource(), relogio, conta -> "Clínica " + conta);

        assertThat(comNome.cabecalhoReceita(CONTA, id)).hasValueSatisfying(c -> {
            assertThat(c.clinica()).isEqualTo("Clínica " + CONTA);
            assertThat(c.paciente()).startsWith("Paciente ");
            assertThat(c.cpf()).hasSize(11);
        });
    }

    @Test
    void chamadasDoDiaVemDaMaisRecenteSoNaPropriaConta() {
        long primeiro = novoAgendamento("MARCADO", "2030-01-07 09:00");
        long segundo = novoAgendamento("MARCADO", "2030-01-07 09:30");
        long nrPrimeiro = adapter.registrarChegada(CONTA, primeiro, "recepcao").id();
        long nrSegundo = adapter.registrarChegada(CONTA, segundo, "recepcao").id();
        adapter.chamar(CONTA, nrPrimeiro, null, null, "recepcao");
        PostgresAtendimentoAdapter depois = new PostgresAtendimentoAdapter(new NamedParameterJdbcTemplate(jdbc),
                jdbc.getDataSource(), Clock.fixed(Instant.from(relogio.instant().plusSeconds(60)), relogio.getZone()));
        depois.chamar(CONTA, nrSegundo, null, "Consultório 3", "recepcao");

        List<Atendimento.Chamada> chamadas = adapter.chamadas(CONTA, LocalDate.from(AGORA), 6);

        assertThat(chamadas).hasSize(2);
        assertThat(chamadas.get(0).chamadoEm()).isAfter(chamadas.get(1).chamadoEm());
        assertThat(chamadas.get(0).paciente()).startsWith("Paciente ");
        assertThat(chamadas).extracting(Atendimento.Chamada::local).containsExactly("Consultório 3", null);
        assertThat(adapter.chamadas(CONTA, LocalDate.from(AGORA), 1)).hasSize(1);
        assertThat(adapter.chamadas("outra", LocalDate.from(AGORA), 6)).isEmpty();
        assertThat(adapter.chamadas(CONTA, LocalDate.from(AGORA).plusDays(1), 6)).isEmpty();
    }

    @Test
    void cabecalhoDoAtendimentoTrazClinicaEPacienteSoNaPropriaConta() {
        Consulta consulta = novaConsultaEmAndamento();
        PostgresAtendimentoAdapter comNome = new PostgresAtendimentoAdapter(new NamedParameterJdbcTemplate(jdbc),
                jdbc.getDataSource(), relogio, conta -> "Clínica " + conta);

        assertThat(comNome.cabecalhoAtendimento(CONTA, consulta.nrAtendimento())).hasValueSatisfying(c -> {
            assertThat(c.clinica()).isEqualTo("Clínica " + CONTA);
            assertThat(c.paciente()).startsWith("Paciente ");
            assertThat(c.cpf()).hasSize(11);
        });
        assertThat(comNome.cabecalhoAtendimento("outra", consulta.nrAtendimento())).isEmpty();
    }

    // --- finalizar (11.3) ---

    @Test
    void finalizarComRascunhoOuSemRegistroLiberadoERecusado() {
        Consulta consulta = novaConsultaEmAndamento();

        assertThat(finalizar(consulta).codigo()).isEqualTo(CodigoNegocio.SEM_REGISTRO_CLINICO);
        long evolucao = criarEvolucao(consulta, "Evolução").id();
        assertThat(finalizar(consulta).codigo()).isEqualTo(CodigoNegocio.RASCUNHO_PENDENTE);
        adapter.liberarEvolucao(CONTA, evolucao, consulta.profissionalId(), "dr");
        criarReceita(consulta, item("Dipirona", "1 cp"));
        assertThat(finalizar(consulta).codigo()).isEqualTo(CodigoNegocio.RASCUNHO_PENDENTE);
        assertThat(statusAtendimento(consulta.nrAtendimento())).isEqualTo("EM_CONSULTA");
    }

    @Test
    void cicloCompletoFinalizaExecutaOAgendamentoEMantemOHorarioOcupado() {
        long profissional = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(profissional, "MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        assertThat(adapter.iniciarConsulta(CONTA, nr, profissional, "dr").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        Consulta consulta = new Consulta(profissional, nr);
        adapter.salvarRascunhoAnamnese(CONTA, nr, profissional, anamnese(consulta, "Tosse", "Há 3 dias"), "dr");
        assertThat(adapter.liberarAnamnese(CONTA, nr, profissional, "dr").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        long evolucao = criarEvolucao(consulta, "Ausculta limpa").id();
        assertThat(adapter.liberarEvolucao(CONTA, evolucao, profissional, "dr").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(incluirDiagnostico(consulta, "J06.9", Atendimento.TipoDiagnostico.PRINCIPAL).codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        long receita = criarReceita(consulta, item("Dipirona", "1 cp de 6/6h")).id();
        assertThat(adapter.liberarReceita(CONTA, receita, profissional, LocalDate.of(2030, 2, 6), "dr").codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);

        Atendimento.Operacao op = adapter.finalizar(CONTA, nr, profissional, Atendimento.MotivoAlta.ALTA_MEDICA,
                new Atendimento.Alta(nr, Atendimento.Desfecho.RETORNO, "Repouso", LocalDate.of(2030, 1, 21), AGORA, "dr"),
                "dr.alta");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(statusAtendimento(nr)).isEqualTo("FINALIZADO");
        assertThat(statusAgendamento(agendamento)).isEqualTo("EXECUTADO");
        assertThat(adapter.atendimento(CONTA, nr)).hasValueSatisfying(f -> {
            assertThat(f.dtAlta()).isEqualTo(AGORA);
            assertThat(f.dtFimConsulta()).isEqualTo(AGORA);
            assertThat(f.motivoAlta()).isEqualTo(Atendimento.MotivoAlta.ALTA_MEDICA);
            assertThat(f.usuarioAlta()).isEqualTo("dr.alta");
        });
        assertThat(adapter.prontuario(CONTA, nr)).hasValueSatisfying(p -> {
            assertThat(p.alta().desfecho()).isEqualTo(Atendimento.Desfecho.RETORNO);
            assertThat(p.alta().retornoPrevisto()).isEqualTo(LocalDate.of(2030, 1, 21));
            assertThat(p.anamnese()).isNotNull();
            assertThat(p.evolucoes()).hasSize(1);
            assertThat(p.diagnosticos()).hasSize(1);
            assertThat(p.receitas()).hasSize(1);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agenda.agendamento_evento WHERE agendamento_id = ? "
                + "AND tipo = 'EXECUTADO'", Integer.class, agendamento)).isEqualTo(1);
        assertThat(adapter.eventos(CONTA, nr)).extracting(Atendimento.Evento::tipo)
                .startsWith("CHEGADA", "CONSULTA_INICIADA").endsWith("FINALIZADO");
        // o índice uk_agendamento_ocupa_horario mantém o horário ocupado depois de EXECUTADO
        long especialidade = jdbc.queryForObject("SELECT especialidade_id FROM agenda.agendamento WHERE id = ?", Long.class,
                agendamento);
        long paciente = jdbc.queryForObject("SELECT paciente_id FROM agenda.agendamento WHERE id = ?", Long.class, agendamento);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agenda.agendamento (conta_id, profissional_id, especialidade_id, "
                + "paciente_id, inicio, fim, status, origem) VALUES (?, ?, ?, ?, TIMESTAMP '2030-01-07 09:00', "
                + "TIMESTAMP '2030-01-07 09:30', 'MARCADO', 'RECEPCAO')", CONTA, profissional, especialidade, paciente))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        // depois de FINALIZADO nenhuma escrita clínica passa (11.2)
        assertThat(criarEvolucao(consulta, "Depois").codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(incluirDiagnostico(consulta, "E11", Atendimento.TipoDiagnostico.SECUNDARIO).codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(criarReceita(consulta, item("X", "Y")).codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.cancelarReceita(CONTA, receita, profissional, "Motivo", "dr").codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(adapter.salvarRascunhoAnamnese(CONTA, nr, profissional, anamnese(consulta, "X", "Y"), "dr").codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        assertThat(finalizar(consulta).codigo()).isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
    }

    // --- finalizar e assinar ---

    @Test
    void finalizarEAssinarLiberaRascunhosComConteudoEDescartaOsVazios() {
        Consulta consulta = novaConsultaEmAndamento();
        long nr = consulta.nrAtendimento();
        adapter.salvarRascunhoAnamnese(CONTA, nr, consulta.profissionalId(), anamnese(consulta, "Tosse", "Há 3 dias"), "dr");
        long vazia = criarEvolucao(consulta, " ").id();
        long receita = criarReceita(consulta, item("Dipirona", "1 cp de 6/6h")).id();
        long receitaVazia = criarReceita(consulta, new Atendimento.ReceitaItem(0, "", "", "", "", "", false, "")).id();

        Atendimento.Operacao op = finalizarEAssinar(consulta);

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(statusAtendimento(nr)).isEqualTo("FINALIZADO");
        assertThat(jdbc.queryForObject("SELECT dt_liberacao IS NOT NULL FROM atendimento.anamnese_paciente "
                + "WHERE nr_atendimento = ? AND ie_situacao = 'A'", Boolean.class, nr)).isTrue();
        assertThat(jdbc.queryForObject("SELECT ie_situacao FROM atendimento.evolucao_paciente WHERE cd_evolucao = ?",
                String.class, vazia)).isEqualTo("I");
        assertThat(jdbc.queryForObject("SELECT dt_validade FROM atendimento.med_receita WHERE nr_sequencia = ?",
                java.sql.Date.class, receita).toLocalDate()).isEqualTo(LocalDate.of(2030, 2, 6));
        assertThat(jdbc.queryForObject("SELECT ie_situacao FROM atendimento.med_receita WHERE nr_sequencia = ?",
                String.class, receitaVazia)).isEqualTo("I");
        assertThat(adapter.eventos(CONTA, nr)).extracting(Atendimento.Evento::tipo)
                .contains("ANAMNESE_LIBERADA", "EVOLUCAO_DESCARTADA", "RECEITA_LIBERADA", "RECEITA_DESCARTADA")
                .endsWith("FINALIZADO");
    }

    @Test
    void finalizarEAssinarComRascunhoIncompletoNaoGravaNada() {
        Consulta consulta = novaConsultaEmAndamento();
        long nr = consulta.nrAtendimento();
        adapter.salvarRascunhoAnamnese(CONTA, nr, consulta.profissionalId(), anamnese(consulta, "Tosse", "Há 3 dias"), "dr");
        criarReceita(consulta, item("Dipirona", " "));

        assertThatThrownBy(() -> finalizarEAssinar(consulta)).isInstanceOf(IllegalArgumentException.class);

        assertThat(statusAtendimento(nr)).isEqualTo("EM_CONSULTA");
        assertThat(jdbc.queryForObject("SELECT dt_liberacao IS NULL FROM atendimento.anamnese_paciente "
                + "WHERE nr_atendimento = ? AND ie_situacao = 'A'", Boolean.class, nr)).isTrue();
    }

    @Test
    void finalizarEAssinarSemRegistroClinicoDesfazOsDescartes() {
        Consulta consulta = novaConsultaEmAndamento();
        long vazia = criarEvolucao(consulta, "").id();

        assertThat(finalizarEAssinar(consulta).codigo()).isEqualTo(CodigoNegocio.SEM_REGISTRO_CLINICO);

        assertThat(jdbc.queryForObject("SELECT ie_situacao FROM atendimento.evolucao_paciente WHERE cd_evolucao = ?",
                String.class, vazia)).isEqualTo("A");
        assertThat(statusAtendimento(consulta.nrAtendimento())).isEqualTo("EM_CONSULTA");
    }

    // --- alergias do paciente ---

    @Test
    void alergiaValeParaOPacienteEApareceNoCabecalho() {
        Consulta consulta = novaConsultaEmAndamento();
        long nr = consulta.nrAtendimento();

        Atendimento.Operacao op = adapter.incluirAlergia(CONTA, nr, consulta.profissionalId(), "Dipirona", "dr");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.incluirAlergia(CONTA, nr, consulta.profissionalId(), " dipirona", "dr").codigo())
                .isEqualTo(CodigoNegocio.ALERGIA_DUPLICADA);
        assertThat(adapter.prontuario(CONTA, nr)).hasValueSatisfying(p -> {
            assertThat(p.paciente().alergias()).extracting(Atendimento.Alergia::descricao).containsExactly("Dipirona");
            assertThat(p.paciente().nascimento()).isNotNull();
        });
        assertThat(adapter.eventos(CONTA, nr)).allSatisfy(e -> assertThat(e.detalhe()).doesNotContain("Dipirona"));

        assertThat(adapter.inativarAlergia(CONTA, nr, op.id(), consulta.profissionalId(), "Registrada por engano", "dr")
                .codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.prontuario(CONTA, nr)).hasValueSatisfying(p -> assertThat(p.paciente().alergias()).isEmpty());
        assertThat(adapter.incluirAlergia(CONTA, nr, consulta.profissionalId(), "Dipirona", "dr").codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
    }

    @Test
    void alergiaForaDeConsultaERecusada() {
        Consulta consulta = novaConsultaEmAndamento();
        voltarParaAguardando(consulta.nrAtendimento());

        assertThat(adapter.incluirAlergia(CONTA, consulta.nrAtendimento(), consulta.profissionalId(), "Látex", "dr").codigo())
                .isEqualTo(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
    }

    @Test
    void historicoDoPacienteTrazAtendimentosDoMaisRecenteSemOsCancelados() {
        Consulta consulta = novaConsultaEmAndamento();
        long paciente = adapter.atendimento(CONTA, consulta.nrAtendimento()).orElseThrow().pacienteId();

        List<Atendimento.Prontuario> historico = adapter.historicoDoPaciente(CONTA, paciente);

        assertThat(historico).extracting(p -> p.atendimento().nrAtendimento()).containsExactly(consulta.nrAtendimento());
        assertThat(adapter.historicoDoPaciente("outra", paciente)).isEmpty();
    }

    // --- auditoria e eventos (12) ---

    @Test
    void registrarAcessoGravaAuditoriaSemConteudo() {
        Consulta consulta = novaConsultaEmAndamento();
        long paciente = adapter.atendimento(CONTA, consulta.nrAtendimento()).orElseThrow().pacienteId();

        adapter.registrarAcesso(CONTA, consulta.nrAtendimento(), paciente, "PRONTUARIO", "dr", "10.0.0.1");
        adapter.registrarAcesso(CONTA, null, paciente, "HISTORICO", "dr", null);

        assertThat(jdbc.queryForList("SELECT acao FROM atendimento.acesso_prontuario WHERE paciente_id = ? ORDER BY id",
                String.class, paciente)).containsExactly("PRONTUARIO", "HISTORICO");
        assertThat(jdbc.queryForObject("SELECT ip FROM atendimento.acesso_prontuario WHERE acao = 'PRONTUARIO'",
                String.class)).isEqualTo("10.0.0.1");
    }

    @Test
    void registrarAcessoGravaOMotivoEVazioViraNulo() {
        Consulta consulta = novaConsultaEmAndamento();
        long paciente = adapter.atendimento(CONTA, consulta.nrAtendimento()).orElseThrow().pacienteId();

        adapter.registrarAcesso(CONTA, null, paciente, "PRONTUARIO_COMPLETO", "dr", "10.0.0.1", "exame trazido");
        adapter.registrarAcesso(CONTA, null, paciente, "PRONTUARIO_COMPLETO", "dr", "10.0.0.1", "  ");

        assertThat(jdbc.queryForList("SELECT ds_motivo FROM atendimento.acesso_prontuario WHERE paciente_id = ? ORDER BY id",
                String.class, paciente)).containsExactly("exame trazido", null);
    }

    // --- consulta do paciente por CPF ---

    @Test
    void pacientesPorCpfSoDaConta() {
        Consulta consulta = novaConsultaEmAndamento();
        long paciente = adapter.atendimento(CONTA, consulta.nrAtendimento()).orElseThrow().pacienteId();
        String cpf = jdbc.queryForObject("SELECT cpf FROM agenda.paciente WHERE id = ?", String.class, paciente);

        assertThat(adapter.pacientesPorCpf(CONTA, cpf)).extracting(Atendimento.PacienteResumo::id).containsExactly(paciente);
        assertThat(adapter.pacientesPorCpf("outra", cpf)).isEmpty();
        assertThat(adapter.paciente(CONTA, paciente)).map(Atendimento.PacienteResumo::cpf).contains(cpf);
        assertThat(adapter.paciente("outra", paciente)).isEmpty();
    }

    @Test
    void visitasDoPacienteTrazLocalDaChamadaESoMostraEventoClinicoQuandoPedido() {
        long profissional = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(profissional, "MARCADO", "2030-01-07 09:00");
        long atendimento = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        adapter.chamar(CONTA, atendimento, null, "Consultório 3", "recepcao");
        adapter.iniciarConsulta(CONTA, atendimento, profissional, "dr");
        Consulta consulta = new Consulta(profissional, atendimento);
        adapter.salvarRascunhoAnamnese(CONTA, atendimento, profissional, anamnese(consulta, "SIGILOSO", "SIGILOSO"), "dr");
        long paciente = adapter.atendimento(CONTA, atendimento).orElseThrow().pacienteId();

        List<Atendimento.VisitaPaciente> administrativas = adapter.visitasDoPaciente(CONTA, paciente, false);
        List<Atendimento.VisitaPaciente> completas = adapter.visitasDoPaciente(CONTA, paciente, true);

        assertThat(administrativas).hasSize(1);
        Atendimento.VisitaPaciente visita = administrativas.get(0);
        assertThat(visita.nrAtendimento()).isEqualTo(atendimento);
        assertThat(visita.localChamada()).isEqualTo("Consultório 3");
        assertThat(visita.eventos()).extracting(Atendimento.EventoPaciente::tipo)
                .contains("CHEGADA", "CHAMADO", "CONSULTA_INICIADA")
                .doesNotContain("ANAMNESE_SALVA", "CHEGOU", "EM_CONSULTA");
        assertThat(completas.get(0).eventos()).extracting(Atendimento.EventoPaciente::tipo).contains("ANAMNESE_SALVA");
        assertThat(completas.get(0).eventos()).allSatisfy(e -> assertThat(String.valueOf(e.detalhe())).doesNotContain("SIGILOSO"));
        assertThat(adapter.visitasDoPaciente("outra", paciente, true)).isEmpty();
    }

    @Test
    void temAtendimentoNoDiaSoDoProfissionalENoDia() {
        Consulta consulta = novaConsultaEmAndamento();
        definirEntrada(consulta.nrAtendimento(), "2030-01-07 09:05");
        long paciente = adapter.atendimento(CONTA, consulta.nrAtendimento()).orElseThrow().pacienteId();
        long outro = novoProfissional(CONTA, null, true);

        assertThat(adapter.temAtendimentoNoDia(CONTA, paciente, consulta.profissionalId(), LocalDate.of(2030, 1, 7))).isTrue();
        assertThat(adapter.temAtendimentoNoDia(CONTA, paciente, consulta.profissionalId(), LocalDate.of(2030, 1, 8))).isFalse();
        assertThat(adapter.temAtendimentoNoDia(CONTA, paciente, outro, LocalDate.of(2030, 1, 7))).isFalse();
        assertThat(adapter.temAtendimentoNoDia("outra", paciente, consulta.profissionalId(), LocalDate.of(2030, 1, 7))).isFalse();
    }

    @Test
    void eventosSoDaContaENuncaComTextoClinico() {
        Consulta consulta = novaConsultaEmAndamento();
        String sigiloso = "SIGILOSO dor torácica";
        adapter.salvarRascunhoAnamnese(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                anamnese(consulta, sigiloso, sigiloso), "dr");
        incluirDiagnostico(consulta, "J06", Atendimento.TipoDiagnostico.PRINCIPAL);
        criarReceita(consulta, item(sigiloso, sigiloso));

        List<Atendimento.Evento> eventos = adapter.eventos(CONTA, consulta.nrAtendimento());

        assertThat(eventos).extracting(Atendimento.Evento::tipo)
                .contains("CHEGADA", "CONSULTA_INICIADA", "ANAMNESE_SALVA", "DIAGNOSTICO_INCLUIDO", "RECEITA_SALVA");
        assertThat(eventos).allSatisfy(e -> assertThat(e.detalhe()).doesNotContain("SIGILOSO").doesNotContain("J06"));
        assertThat(adapter.eventos("outra", consulta.nrAtendimento())).isEmpty();
    }

    private Atendimento.Operacao incluirDiagnostico(Consulta consulta, String cid, Atendimento.TipoDiagnostico tipo) {
        return adapter.incluirDiagnostico(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                new Atendimento.Diagnostico(null, consulta.nrAtendimento(), 0, consulta.profissionalId(), cid, "Descrição",
                        tipo, AGORA, true, null, null, null, "dr"), "dr");
    }

    private Atendimento.Operacao criarReceita(Consulta consulta, Atendimento.ReceitaItem... itens) {
        return adapter.criarReceita(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                receita(consulta, "", itens), "dr");
    }

    private Atendimento.Receita receita(Consulta consulta, String observacao, Atendimento.ReceitaItem... itens) {
        return new Atendimento.Receita(null, consulta.nrAtendimento(), 0, consulta.profissionalId(),
                Atendimento.TipoReceita.COMUM, observacao, AGORA, null, null, true, null, null, null, "dr", List.of(itens));
    }

    private static Atendimento.ReceitaItem item(String medicamento, String posologia) {
        return new Atendimento.ReceitaItem(0, medicamento, "500 mg", posologia, "Oral", "1 caixa", false, "");
    }

    private Atendimento.Operacao finalizar(Consulta consulta) {
        return adapter.finalizar(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                Atendimento.MotivoAlta.ALTA_MEDICA,
                new Atendimento.Alta(consulta.nrAtendimento(), null, "", null, AGORA, "dr"), "dr");
    }

    private Atendimento.Operacao finalizarEAssinar(Consulta consulta) {
        return adapter.finalizarEAssinar(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                Atendimento.MotivoAlta.ALTA_MEDICA,
                new Atendimento.Alta(consulta.nrAtendimento(), null, "", null, AGORA, "dr"), LocalDate.of(2030, 2, 6), "dr");
    }

    private Atendimento.Operacao criarEvolucao(Consulta consulta, String texto) {
        return adapter.criarEvolucao(CONTA, consulta.nrAtendimento(), consulta.profissionalId(),
                evolucao(consulta, texto, null, null, null), "dr");
    }

    private Atendimento.Evolucao evolucao(Consulta consulta, String texto, java.math.BigDecimal peso,
                                          java.math.BigDecimal altura, LocalDate retorno) {
        return new Atendimento.Evolucao(null, consulta.nrAtendimento(), 0, consulta.profissionalId(), texto, peso, altura,
                retorno, AGORA, null, true, null, null, null, "dr");
    }

    private Consulta novaConsultaEmAndamento() {
        long profissional = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(profissional, "MARCADO", "2030-01-07 09:00");
        long atendimento = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        assertThat(adapter.iniciarConsulta(CONTA, atendimento, profissional, "dr").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        return new Consulta(profissional, atendimento);
    }

    private Atendimento.Anamnese anamnese(Consulta consulta, String queixa, String historia) {
        return new Atendimento.Anamnese(null, consulta.nrAtendimento(), 0, consulta.profissionalId(), queixa, historia,
                "Antecedentes pessoais", "Antecedentes familiares", "Medicamento", "Alergia", "Hábitos", "Exame físico",
                "Avaliação", "Conduta", AGORA, null, true, null, null, null, "dr");
    }

    private void voltarParaAguardando(long atendimento) {
        jdbc.update("UPDATE atendimento.atendimento_paciente SET ie_status_atendimento = 'AGUARDANDO' WHERE nr_atendimento = ?",
                atendimento);
    }

    private record Consulta(long profissionalId, long nrAtendimento) {
    }

    private void definirEntrada(long atendimento, String entrada) {
        jdbc.update("UPDATE atendimento.atendimento_paciente SET dt_entrada = ?::timestamp WHERE nr_atendimento = ?", entrada,
                atendimento);
    }

    // --- recepção: chamada, falta e encaixe ---

    @Test
    void chamarGravaHorarioEventoEApareceNaFila() {
        long profissional = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(profissional, "MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();

        assertThat(adapter.chamar(CONTA, nr, profissional + 1, null, "x").codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.chamar("outra", nr, null, null, "x").codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.chamar(CONTA, nr, profissional, "Consultório 3", "dr").codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        assertThat(adapter.fila(CONTA, AGORA.toLocalDate())).singleElement().satisfies(f -> {
            assertThat(f.chamadoEm()).isEqualTo(AGORA);
            assertThat(f.cpf()).hasSize(11);
            assertThat(f.status()).isEqualTo("AGUARDANDO");
            assertThat(f.localChamada()).isEqualTo("Consultório 3");
        });
        assertThat(jdbc.queryForObject("SELECT detalhe FROM atendimento.atendimento_evento WHERE nr_atendimento = ? AND tipo = 'CHAMADO'",
                String.class, nr)).isEqualTo("Consultório 3");
        adapter.iniciarConsulta(CONTA, nr, profissional, "dr");
        assertThat(adapter.chamar(CONTA, nr, null, null, "x").codigo()).isEqualTo(CodigoNegocio.STATUS_INVALIDO_PARA_CHAMADA);
    }

    @Test
    void recepcaoChamaPacienteJaAtendidoMasOMedicoNao() {
        long profissional = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(profissional, "MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        jdbc.update("UPDATE atendimento.atendimento_paciente SET ie_status_atendimento = 'FINALIZADO', "
                + "dt_alta = CURRENT_TIMESTAMP, ie_motivo_alta = 'ALTA_MEDICA' WHERE nr_atendimento = ?", nr);

        assertThat(adapter.chamar(CONTA, nr, profissional, "Consultório 3", "dr").codigo())
                .isEqualTo(CodigoNegocio.STATUS_INVALIDO_PARA_CHAMADA);
        assertThat(adapter.chamar(CONTA, nr, null, "Financeiro", "recepcao").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.chamadas(CONTA, AGORA.toLocalDate(), 6)).singleElement()
                .extracting(Atendimento.Chamada::local).isEqualTo("Financeiro");
    }

    @Test
    void faltaSoDepoisDoHorarioELiberaOHorarioAteDesfazer() {
        long profissional = novoProfissional(CONTA, null, true);
        long futuro = novoAgendamento(profissional, "MARCADO", "2030-01-07 11:00");
        long passado = novoAgendamento(profissional, "CONFIRMADO", "2030-01-07 09:00");
        jdbc.update("INSERT INTO agenda.agendamento_evento (agendamento_id, tipo, usuario) VALUES (?, 'CONFIRMADO', 'bot')", passado);

        assertThat(adapter.marcarFalta(CONTA, futuro, "rec").codigo()).isEqualTo(CodigoNegocio.FALTA_ANTES_DO_HORARIO);
        assertThat(adapter.marcarFalta("outra", passado, "rec").codigo()).isEqualTo(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        assertThat(adapter.marcarFalta(CONTA, passado, "rec").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(statusAgendamento(passado)).isEqualTo("FALTOU");
        assertThat(adapter.registrarChegada(CONTA, passado, "rec").codigo()).isEqualTo(CodigoNegocio.STATUS_INVALIDO_PARA_CHEGADA);
        assertThat(adapter.fila(CONTA, AGORA.toLocalDate())).extracting(Atendimento.FilaItem::status).contains("FALTOU");

        assertThat(adapter.desfazerFalta(CONTA, passado, "rec").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(statusAgendamento(passado)).isEqualTo("CONFIRMADO");
        assertThat(adapter.desfazerFalta(CONTA, passado, "rec").codigo()).isEqualTo(CodigoNegocio.STATUS_INVALIDO_PARA_FALTA);
    }

    @Test
    void desfazerFaltaComHorarioJaOcupadoERecusado() {
        long profissional = novoProfissional(CONTA, null, true);
        long faltou = novoAgendamento(profissional, "MARCADO", "2030-01-07 09:00");
        adapter.marcarFalta(CONTA, faltou, "rec");
        long outro = novoAgendamento(profissional, "MARCADO", "2030-01-07 09:00");

        assertThat(adapter.desfazerFalta(CONTA, faltou, "rec").codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
        assertThat(statusAgendamento(faltou)).isEqualTo("FALTOU");
        assertThat(statusAgendamento(outro)).isEqualTo("MARCADO");
    }

    @Test
    void encaixeCadastraPacienteECriaAtendimentoAguardandoMesmoComHorarioOcupado() {
        long profissional = novoProfissional(CONTA, null, true);
        long ocupado = novoAgendamento(profissional, "MARCADO", "2030-01-07 10:00");
        long especialidade = jdbc.queryForObject("SELECT especialidade_id FROM agenda.agendamento WHERE id = ?", Long.class, ocupado);
        jdbc.update("INSERT INTO agenda.profissional_especialidade (profissional_id, especialidade_id) VALUES (?, ?)",
                profissional, especialidade);
        Atendimento.Encaixe encaixe = new Atendimento.Encaixe(profissional, especialidade, null, "98765432100",
                LocalDate.of(1990, 5, 1), "Walk In", "11999990000");

        Atendimento.Operacao primeiro = adapter.encaixe(CONTA, encaixe, 15, "rec");
        Atendimento.Operacao segundo = adapter.encaixe(CONTA, encaixe, 15, "rec");

        assertThat(primeiro.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(segundo.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(statusAtendimento(primeiro.id())).isEqualTo("AGUARDANDO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agenda.paciente WHERE cpf = '98765432100'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT inicio FROM agenda.agendamento WHERE profissional_id = ? AND status = 'AGUARDANDO' ORDER BY inicio",
                java.sql.Timestamp.class, profissional)).extracting(java.sql.Timestamp::toLocalDateTime)
                .containsExactly(AGORA.plusSeconds(1), AGORA.plusSeconds(2));
        assertThat(adapter.atendimento(CONTA, primeiro.id())).hasValueSatisfying(f -> assertThat(f.paciente()).isEqualTo("Walk In"));
        assertThat(adapter.buscarPacientes(CONTA, "walk", 10)).extracting(Atendimento.PacienteResumo::cpf).containsExactly("98765432100");
        assertThat(adapter.buscarPacientes(CONTA, "987.654", 10)).hasSize(1);
        assertThat(adapter.buscarPacientes("outra", "walk", 10)).isEmpty();
        assertThat(adapter.cadastrosEncaixe(CONTA).profissionais()).anySatisfy(p -> {
            assertThat(p.id()).isEqualTo(profissional);
            assertThat(p.especialidades()).extracting(Atendimento.Opcao::id).containsExactly(especialidade);
        });
    }

    @Test
    void encaixeComEspecialidadeNaoVinculadaNaoGravaNada() {
        long profissional = novoProfissional(CONTA, null, true);
        long especialidade = jdbc.queryForObject("INSERT INTO agenda.especialidade (conta_id, nome) VALUES (?, 'Sem vínculo') RETURNING id",
                Long.class, CONTA);

        Atendimento.Operacao op = adapter.encaixe(CONTA, new Atendimento.Encaixe(profissional, especialidade, null,
                "98765432100", LocalDate.of(1990, 5, 1), "Walk In", ""), 15, "rec");

        assertThat(op.codigo()).isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agenda.paciente WHERE cpf = '98765432100'", Integer.class)).isZero();
    }

    // --- favoritos e modelos ---

    @Test
    void favoritoRepetidoAtualizaEFicaSoDoProfissional() {
        long profissional = novoProfissional(CONTA, null, true);
        long outro = novoProfissional(CONTA, null, true);
        long id = adapter.salvarFavorito(CONTA, profissional, new Atendimento.MedicamentoFavorito(null, "Dipirona", "500 mg",
                "1 cp 6/6h", "Oral", "10", false, ""));
        long mesmo = adapter.salvarFavorito(CONTA, profissional, new Atendimento.MedicamentoFavorito(null, " dipirona ", "500 MG",
                "1 cp 6/6h", "Oral", "20", true, ""));

        assertThat(mesmo).isEqualTo(id);
        assertThat(adapter.favoritos(CONTA, profissional)).singleElement().satisfies(f -> {
            assertThat(f.quantidade()).isEqualTo("20");
            assertThat(f.usoContinuo()).isTrue();
        });
        assertThat(adapter.favoritos(CONTA, outro)).isEmpty();
        assertThat(adapter.excluirFavorito(CONTA, outro, id)).isFalse();
        assertThat(adapter.excluirFavorito(CONTA, profissional, id)).isTrue();
        assertThat(adapter.favoritos(CONTA, profissional)).isEmpty();
    }

    @Test
    void modeloComAtalhoRepetidoERecusado() {
        long profissional = novoProfissional(CONTA, null, true);
        long outro = novoProfissional(CONTA, null, true);
        long id = adapter.salvarModelo(CONTA, profissional, new Atendimento.ModeloNota(null, "normal", "Exame", "BEG")).id();

        assertThat(adapter.salvarModelo(CONTA, profissional, new Atendimento.ModeloNota(null, "normal", "", "Outro")).codigo())
                .isEqualTo(CodigoNegocio.ATALHO_DUPLICADO);
        assertThat(adapter.salvarModelo(CONTA, outro, new Atendimento.ModeloNota(null, "normal", "", "Dele")).codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.salvarModelo(CONTA, outro, new Atendimento.ModeloNota(id, "x1", "", "Invasão")).codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.salvarModelo(CONTA, profissional, new Atendimento.ModeloNota(id, "normal", "Exame", "BEG, corado")).codigo())
                .isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.modelos(CONTA, profissional)).singleElement()
                .satisfies(m -> assertThat(m.texto()).isEqualTo("BEG, corado"));
        assertThat(adapter.excluirModelo(CONTA, profissional, id)).isTrue();
    }

    // --- dados de apoio ---

    private int totalAtendimentos(long agendamento) {
        return jdbc.queryForObject("SELECT count(*) FROM atendimento.atendimento_paciente WHERE agendamento_id = ? "
                + "AND ie_status_atendimento <> 'CANCELADO'", Integer.class, agendamento);
    }

    private String statusAgendamento(long agendamento) {
        return jdbc.queryForObject("SELECT status FROM agenda.agendamento WHERE id = ?", String.class, agendamento);
    }

    private String statusAtendimento(long atendimento) {
        return jdbc.queryForObject("SELECT ie_status_atendimento FROM atendimento.atendimento_paciente WHERE nr_atendimento = ?",
                String.class, atendimento);
    }

    // --- documentos ---

    @Test
    void emitirDocumentoGravaEventoELista() {
        long medico = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(medico, "MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        long paciente = adapter.atendimento(CONTA, nr).orElseThrow().pacienteId();

        Atendimento.Operacao atestado = adapter.emitirDocumento(CONTA, new Atendimento.Documento(null, nr, paciente, medico,
                Atendimento.TipoDocumento.ATESTADO, "Atesto 2 dias", 2, "J06.9", AGORA, "dr"));
        Atendimento.Operacao declaracao = adapter.emitirDocumento(CONTA, new Atendimento.Documento(null, nr, paciente, null,
                Atendimento.TipoDocumento.DECLARACAO, "Compareceu", null, null, AGORA.plusMinutes(5), "recepcao"));

        assertThat(atestado.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(declaracao.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adapter.documentos(CONTA, nr)).extracting(Atendimento.Documento::tipo)
                .containsExactly(Atendimento.TipoDocumento.DECLARACAO, Atendimento.TipoDocumento.ATESTADO);
        Atendimento.Documento lido = adapter.documento(CONTA, atestado.id()).orElseThrow();
        assertThat(lido).isEqualTo(new Atendimento.Documento(atestado.id(), nr, paciente, medico,
                Atendimento.TipoDocumento.ATESTADO, "Atesto 2 dias", 2, "J06.9", AGORA, "dr"));
        assertThat(adapter.documento(CONTA, declaracao.id()).orElseThrow().dias()).isNull();
        assertThat(adapter.documento("outra", atestado.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atendimento.atendimento_evento WHERE nr_atendimento = ? "
                + "AND tipo IN ('ATESTADO_EMITIDO', 'DECLARACAO_EMITIDA')", Integer.class, nr)).isEqualTo(2);
        assertThat(adapter.visitasDoPaciente(CONTA, paciente, false).get(0).eventos())
                .extracting(Atendimento.EventoPaciente::tipo).contains("DECLARACAO_EMITIDA").doesNotContain("ATESTADO_EMITIDO");
    }

    @Test
    void documentoDeOutroProfissionalOuDeAtendimentoCanceladoNaoEGravado() {
        long medico = novoProfissional(CONTA, null, true);
        long outro = novoProfissional(CONTA, null, true);
        long agendamento = novoAgendamento(medico, "MARCADO", "2030-01-07 09:00");
        long nr = adapter.registrarChegada(CONTA, agendamento, "recepcao").id();
        long paciente = adapter.atendimento(CONTA, nr).orElseThrow().pacienteId();

        assertThat(adapter.emitirDocumento(CONTA, new Atendimento.Documento(null, nr, paciente, outro,
                Atendimento.TipoDocumento.ATESTADO, "Atesto", null, null, AGORA, "dr")).codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        adapter.desfazerChegada(CONTA, nr, "engano", "recepcao");
        assertThat(adapter.emitirDocumento(CONTA, new Atendimento.Documento(null, nr, paciente, null,
                Atendimento.TipoDocumento.DECLARACAO, "Compareceu", null, null, AGORA, "recepcao")).codigo())
                .isEqualTo(CodigoNegocio.NAO_ENCONTRADO);
        assertThat(adapter.documentos(CONTA, nr)).isEmpty();
    }

    private long novoProfissional(String conta, Long usuarioId, boolean ativo) {
        return jdbc.queryForObject("INSERT INTO agenda.profissional (conta_id, nome, ativo, usuario_id, conselho_numero, conselho_uf) "
                + "VALUES (?, ?, ?, ?, '12345', 'SP') RETURNING id", Long.class, conta, "Dr. " + sequencia.incrementAndGet(), ativo,
                usuarioId);
    }

    private long novoUsuario() {
        int n = sequencia.incrementAndGet();
        return jdbc.queryForObject("INSERT INTO acesso.usuario (nome, email, senha_hash, perfil, criado_por) "
                + "VALUES (?, ?, 'x', 'MEDICO', 'it') RETURNING id", Long.class, "Medico " + n, "m" + n + "@it.com");
    }

    private long novoAgendamento(String status, String inicio) {
        return novoAgendamento(novoProfissional(CONTA, null, true), status, inicio);
    }

    private long novoAgendamento(long profissional, String status, String inicio) {
        int n = sequencia.incrementAndGet();
        long especialidade = jdbc.queryForObject("INSERT INTO agenda.especialidade (conta_id, nome) VALUES (?, ?) RETURNING id",
                Long.class, CONTA, "Esp " + n);
        long paciente = jdbc.queryForObject("INSERT INTO agenda.paciente (conta_id, cpf, nascimento, nome) "
                + "VALUES (?, ?, DATE '1988-10-19', ?) RETURNING id", Long.class, CONTA, String.format("%011d", n), "Paciente " + n);
        return jdbc.queryForObject("INSERT INTO agenda.agendamento (conta_id, profissional_id, especialidade_id, paciente_id, inicio, fim, "
                + "status, origem) VALUES (?, ?, ?, ?, ?::timestamp, ?::timestamp + INTERVAL '30 minutes', ?, 'RECEPCAO') RETURNING id",
                Long.class, CONTA, profissional, especialidade, paciente, inicio, inicio, status);
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
