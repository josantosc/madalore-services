package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;

@SpringBootTest(properties = {"agenda.enabled=true", "agenda.origem=tasy", "agenda.user=r", "agenda.password=p"})
@AutoConfigureMockMvc
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresAgendaAdapterIT {

    private static final String LOGIN = "Basic " + Base64.getEncoder().encodeToString("r:p".getBytes(StandardCharsets.UTF_8));
    private static final LocalDate DIA = LocalDate.now().plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    @Autowired
    private PostgresAgendaAdapter agenda;

    @Autowired
    @Qualifier("observabilityJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private AgendaConsultaPort consulta;

    @Autowired
    private AgendamentoEscritaPort escrita;

    @Autowired
    private MockMvc mvc;

    private final String conta = "it-" + UUID.randomUUID().toString().substring(0, 8);
    private long profissional;
    private long especialidade;

    @BeforeEach
    void cadastrar() {
        profissional = agenda.salvarItem(conta, Agenda.Cadastro.PROFISSIONAL, new Agenda.Item(null, "Dra. Ana", true)).id();
        especialidade = agenda.salvarItem(conta, Agenda.Cadastro.ESPECIALIDADE, new Agenda.Item(null, "Cardiologia", true)).id();
        agenda.salvarItem(conta, Agenda.Cadastro.CONVENIO, new Agenda.Item(null, "Particular", true));
        agenda.salvarGrade(conta, new Agenda.Grade(null, profissional, especialidade, 1, LocalTime.of(8, 0), LocalTime.of(12, 0), 30, true, "Sala 1", true));
    }

    @AfterEach
    void limpar() {
        MapSqlParameterSource p = new MapSqlParameterSource("conta", conta);
        jdbc.update("DELETE FROM agenda.conferencia WHERE agendamento_id IN (SELECT id FROM agenda.agendamento WHERE conta_id = :conta)", p);
        jdbc.update("DELETE FROM agenda.pendencia WHERE conta_id = :conta", p);
        jdbc.update("DELETE FROM agenda.agendamento_evento WHERE agendamento_id IN (SELECT id FROM agenda.agendamento WHERE conta_id = :conta)", p);
        jdbc.update("UPDATE agenda.agendamento SET remarcado_de = NULL WHERE conta_id = :conta", p);
        jdbc.update("DELETE FROM agenda.agendamento WHERE conta_id = :conta", p);
        jdbc.update("DELETE FROM agenda.paciente WHERE conta_id = :conta", p);
        jdbc.update("DELETE FROM agenda.bloqueio WHERE conta_id = :conta", p);
        jdbc.update("DELETE FROM agenda.grade WHERE conta_id = :conta", p);
        for (String tabela : List.of("profissional", "especialidade", "convenio")) {
            jdbc.update("DELETE FROM agenda." + tabela + " WHERE conta_id = :conta", p);
        }
        jdbc.update("DELETE FROM agenda.operacao WHERE chave LIKE :prefixo", new MapSqlParameterSource("prefixo", conta + "%"));
    }

    @Test
    void marcarPeloBotCriaPendenciaDeLancarUmaVezSo() {
        Agenda.Operacao primeira = agenda.marcar(conta, marcacao(conta + ":k1", 9, 0, null));
        Agenda.Operacao repetida = agenda.marcar(conta, marcacao(conta + ":k1", 9, 0, null));
        Agenda.Operacao outra = agenda.marcar(conta, marcacao(conta + ":k2", 9, 0, null));

        assertThat(primeira.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(primeira.pendencias()).singleElement().satisfies(p -> {
            assertThat(p.tipo()).isEqualTo(Agenda.TipoPendencia.LANCAR);
            assertThat(p.resumo()).contains("CPF: 529.982.247-25", "Profissional: Dra. Ana", "às 09:00", "Origem: bot do WhatsApp");
            assertThat(p.conversaId()).isEqualTo("c1");
        });
        assertThat(repetida.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(outra.codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
        assertThat(ativos()).hasSize(1);
        assertThat(agenda.pendencias(conta, Agenda.SituacaoPendencia.ABERTA)).hasSize(1);
        assertThat(agenda.pendenciasAbertasDaConversa(conta, "c1")).isEqualTo(1);
    }

    @Test
    void cancelarAntesDeLancarDescartaSemCriarOutraPendencia() {
        long id = agenda.marcar(conta, marcacao(null, 9, 0, null)).agendamentoId();

        Agenda.Operacao cancelamento = agenda.cancelar(conta, id, alteracao(false));

        assertThat(cancelamento.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(cancelamento.pendencias()).isEmpty();
        assertThat(agenda.pendencias(conta, Agenda.SituacaoPendencia.ABERTA)).isEmpty();
        assertThat(agenda.pendencias(conta, Agenda.SituacaoPendencia.DESCARTADA)).singleElement()
                .satisfies(p -> assertThat(p.observacao()).contains("cancelado antes"));
        assertThat(agenda.marcar(conta, marcacao(null, 9, 0, null)).codigo()).as("horário volta a ficar livre").isEqualTo(CodigoNegocio.SUCESSO);
    }

    @Test
    void remarcarDepoisDeLancadoPedeRemarcarComONumeroDoSistemaECancelarApontaParaOOriginal() {
        long id = agenda.marcar(conta, marcacao(null, 9, 0, null)).agendamentoId();
        long lancar = agenda.pendencias(conta, Agenda.SituacaoPendencia.ABERTA).get(0).id();
        assertThat(agenda.concluirPendencia(conta, lancar, "ana", "X1", "")).isTrue();
        assertThat(agenda.agendamento(conta, id).orElseThrow().codigoExterno()).isEqualTo("X1");

        Agenda.Operacao remarcacao = agenda.remarcar(conta, id, marcacao(null, 10, 30, null), alteracao(false));

        assertThat(remarcacao.codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(agenda.agendamento(conta, id).orElseThrow().status()).isEqualTo(Agenda.Status.REMARCADO);
        long nova = remarcacao.agendamentoId();
        assertThat(agenda.agendamento(conta, nova).orElseThrow().remarcadoDe()).isEqualTo(id);
        assertThat(remarcacao.pendencias()).singleElement().satisfies(p -> {
            assertThat(p.tipo()).isEqualTo(Agenda.TipoPendencia.REMARCAR);
            assertThat(p.resumo()).contains("Código no sistema da clínica: X1", "às 09:00", "às 10:30");
        });

        Agenda.Operacao cancelamento = agenda.cancelar(conta, nova, alteracao(false));

        assertThat(cancelamento.pendencias()).singleElement().satisfies(p -> {
            assertThat(p.tipo()).isEqualTo(Agenda.TipoPendencia.CANCELAR);
            assertThat(p.resumo()).contains("Código no sistema da clínica: X1", "às 09:00").doesNotContain("10:30");
        });
        assertThat(agenda.pendencias(conta, Agenda.SituacaoPendencia.ABERTA)).extracting(Agenda.Pendencia::tipo)
                .containsExactly(Agenda.TipoPendencia.CANCELAR);
        assertThat(agenda.historico(conta, nova)).extracting(Agenda.Evento::tipo).contains("REMARCADO_DE", "CANCELADO");
    }

    @Test
    void recepcaoQueJaLancouNaoGeraPendencia() {
        Agenda.Operacao marcada = agenda.marcar(conta, marcacao(null, 11, 0, "Y9"));
        long id = marcada.agendamentoId();

        assertThat(marcada.pendencias()).isEmpty();
        assertThat(agenda.agendamento(conta, id).orElseThrow().codigoExterno()).isEqualTo("Y9");
        assertThat(agenda.cancelar(conta, id, alteracao(true)).pendencias()).isEmpty();
        assertThat(agenda.pendencias(conta, null)).isEmpty();
    }

    @Test
    void divergenciaNaConferenciaViraPendenciaEEntraNoResumo() {
        long id = agenda.marcar(conta, marcacao(null, 8, 0, "Z1")).agendamentoId();

        agenda.conferir(conta, id, Agenda.SituacaoConferencia.DIVERGENTE, "No sistema está às 8h30", "ana");
        agenda.conferir(conta, id, Agenda.SituacaoConferencia.DIVERGENTE, "Continua diferente", "ana");

        assertThat(agenda.pendencias(conta, Agenda.SituacaoPendencia.ABERTA)).singleElement()
                .satisfies(p -> assertThat(p.resumo()).contains("Conferir divergência", "No sistema está às 8h30"));
        Agenda.ResumoPendencias resumo = agenda.resumo(conta, LocalDateTime.now().plusYears(1));
        assertThat(resumo.abertas()).isEqualTo(1);
        assertThat(resumo.atrasadas()).isEqualTo(1);
        assertThat(resumo.divergencias()).isEqualTo(1);
        assertThat(agenda.agendamento(conta, id).orElseThrow().conferencia()).isEqualTo(Agenda.SituacaoConferencia.DIVERGENTE);
    }

    @Test
    void contextoSobeComOBotRoteadoETelaResponde() throws Exception {
        assertThat(consulta).isInstanceOf(AgendaRoteamentoConfig.ConsultaRoteada.class);
        assertThat(escrita).isInstanceOf(AgendaRoteamentoConfig.EscritaRoteada.class);
        agenda.marcar(conta, marcacao(null, 9, 0, null));

        mvc.perform(get("/agenda/api/dia").param("conta", conta).param("data", DIA.toString()).header("Authorization", LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas.length()").value(8))
                .andExpect(jsonPath("$.linhas[2].situacao").value("OCUPADO"))
                .andExpect(jsonPath("$.semana.length()").value(7));
        mvc.perform(get("/agenda/api/pendencias").param("conta", conta).header("Authorization", LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumo.abertas").value(1));
    }

    private List<Agenda.Agendamento> ativos() {
        return agenda.agendamentos(conta, DIA.atStartOfDay(), DIA.plusDays(1).atStartOfDay(), true);
    }

    private Agenda.Marcacao marcacao(String chave, int hora, int minuto, String codigo) {
        LocalDateTime inicio = DIA.atTime(hora, minuto);
        return new Agenda.Marcacao(chave, profissional, especialidade, null, inicio, inicio.plusMinutes(30), "Sala 1",
                new Agenda.Paciente(null, "52998224725", LocalDate.of(1988, 10, 19), "Maria da Silva", "91999990000"),
                codigo == null ? Agenda.Origem.BOT : Agenda.Origem.RECEPCAO, codigo == null ? "bot" : "ana", "c1", codigo);
    }

    private Agenda.Alteracao alteracao(boolean jaFeito) {
        return new Agenda.Alteracao(null, null, Agenda.Origem.RECEPCAO, "ana", jaFeito);
    }
}
