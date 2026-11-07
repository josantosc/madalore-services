package br.com.mandalore.agendamentoconversa.adapter.out.funil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil.Etapa;

@SpringBootTest(properties = {"funil.enabled=true", "funil.user=f", "funil.password=p"})
@AutoConfigureMockMvc
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresFunilAdapterIT {

    private static final String CONTA = "it-funil";
    private static final String CONTA_REGRAS = "it-funil-regras";
    private static final String LOGIN = "Basic " + Base64.getEncoder().encodeToString("f:p".getBytes(StandardCharsets.UTF_8));
    private static final LocalDate HOJE = LocalDate.now();

    @Autowired
    private PostgresFunilAdapter funil;

    @Autowired
    private PostgresRegrasFunilAdapter regras;

    @Autowired
    @Qualifier("observabilityJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @AfterEach
    void limpar() {
        MapSqlParameterSource conta = new MapSqlParameterSource("conta", CONTA);
        jdbc.update("""
                DELETE FROM funil.evento WHERE oportunidade_id IN (SELECT id FROM funil.oportunidade WHERE conta_id = :conta)
                """, conta);
        jdbc.update("DELETE FROM funil.oportunidade WHERE conta_id = :conta", conta);
        MapSqlParameterSource contaRegras = new MapSqlParameterSource("conta", CONTA_REGRAS);
        jdbc.update("DELETE FROM funil.regra_evento WHERE conta_id = :conta", contaRegras);
        jdbc.update("DELETE FROM funil.regra WHERE conta_id = :conta", contaRegras);
        jdbc.update("DELETE FROM observability.flow_event WHERE conversation_id LIKE 'it-funil-%'", conta);
    }

    @Test
    void contatoGuardaPacienteEResponsavelSemApagarComNulo() {
        long id = funil.abrir(CONTA, "c9", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");

        funil.contato(id, "Maria", "Bruna");
        funil.contato(id, null, "Carla");

        assertThat(funil.buscar(id)).hasValueSatisfying(o -> {
            assertThat(o.paciente()).isEqualTo("Maria");
            assertThat(o.responsavel()).isEqualTo("Carla");
        });
    }

    @Test
    void valorInformadoNaTelaFicaNoHistorico() throws Exception {
        long id = funil.abrir(CONTA, "c8", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");

        mvc.perform(post("/funil/api/oportunidades/" + id + "/valor").header("Authorization", LOGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\": \"150.5\"}"))
                .andExpect(status().isNoContent());

        assertThat(funil.buscar(id)).hasValueSatisfying(o -> assertThat(o.valor()).isEqualByComparingTo("150.50"));
        assertThat(jdbc.queryForObject("SELECT detalhe FROM funil.evento WHERE oportunidade_id = :id ORDER BY id DESC LIMIT 1",
                new MapSqlParameterSource("id", id), String.class)).isEqualTo("VALOR 150.50");
        assertThat(funil.valor(-1, BigDecimal.ONE, null, "f")).isFalse();
    }

    @Test
    void orcamentoPorTextoTemOrigemPropria() {
        long id = funil.abrir(CONTA, "c6", Funil.Origem.ORCAMENTO_TEXTO, new BigDecimal("25"), 1, HOJE, "mandalore");

        assertThat(funil.buscar(id)).hasValueSatisfying(o -> assertThat(o.origem()).isEqualTo(Funil.Origem.ORCAMENTO_TEXTO));
    }

    @Test
    void valorDoComandoGravaQuantidadeEValorDaTelaMantem() {
        long id = funil.abrir(CONTA, "c7", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");

        funil.valor(id, new BigDecimal("37.00"), 3, "Ana");
        funil.valor(id, new BigDecimal("30.00"), null, "Ana");

        assertThat(funil.buscar(id)).hasValueSatisfying(o -> {
            assertThat(o.valor()).isEqualByComparingTo("30");
            assertThat(o.qtdItens()).isEqualTo(3);
        });
        assertThat(jdbc.queryForList("SELECT detalhe FROM funil.evento WHERE oportunidade_id = :id ORDER BY id",
                new MapSqlParameterSource("id", id), String.class))
                .containsExactly("ORCAMENTO_MANUAL", "VALOR 37.00 EXAMES 3", "VALOR 30.00");
    }

    @Test
    void segundoOrcamentoDaMesmaConversaAtualizaAOportunidadeAberta() {
        long id = funil.abrir(CONTA, "c1", Funil.Origem.ORCAMENTO_FOTO, new BigDecimal("37"), 2, HOJE, "mandalore");
        long mesmo = funil.abrir(CONTA, "c1", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");

        assertThat(mesmo).isEqualTo(id);
        assertThat(funil.aberta(CONTA, "c1")).hasValueSatisfying(o -> {
            assertThat(o.origem()).isEqualTo(Funil.Origem.ORCAMENTO_FOTO);
            assertThat(o.etapa()).isEqualTo(Etapa.NOVO);
            assertThat(o.valor()).isEqualByComparingTo("37");
            assertThat(o.qtdItens()).isEqualTo(2);
            assertThat(o.proximaAcao()).isEqualTo(HOJE);
        });
        assertThat(eventos(id)).isEqualTo(1);
    }

    @Test
    void mudarConfereAEtapaAtualEFecharZeraORetorno() {
        long id = funil.abrir(CONTA, "c2", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");

        assertThat(funil.mudar(id, Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null, HOJE.plusDays(2), 1, "Ana", "Ana")).isTrue();
        assertThat(funil.buscar(id).orElseThrow().toque()).isEqualTo(1);
        assertThat(funil.mudar(id, Etapa.NOVO, Etapa.AGENDADO, null, null, null, null, "outro")).isFalse();
        assertThat(funil.mudar(id, Etapa.ORCAMENTO_ENVIADO, Etapa.PERDIDO, Funil.MotivoPerda.PRECO, HOJE, null, null, "ana")).isTrue();

        Funil.Oportunidade perdida = funil.buscar(id).orElseThrow();
        assertThat(perdida.etapa()).isEqualTo(Etapa.PERDIDO);
        assertThat(perdida.motivoPerda()).isEqualTo(Funil.MotivoPerda.PRECO);
        assertThat(perdida.responsavel()).isEqualTo("Ana");
        assertThat(perdida.proximaAcao()).isNull();
        assertThat(perdida.fechadaEm()).isNotNull();
        assertThat(funil.aberta(CONTA, "c2")).isEmpty();

        long nova = funil.abrir(CONTA, "c2", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        assertThat(nova).isNotEqualTo(id);
        assertThatThrownBy(() -> funil.mudar(id, Etapa.PERDIDO, Etapa.EM_NEGOCIACAO, null, HOJE, 1, null, "ana"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cadenciaAvancaEncerraEFechaNoDiaDoEncerramento() {
        long id = funil.abrir(CONTA, "c8", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        funil.mudar(id, Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null, HOJE, 1, "Ana", "Ana");

        funil.avancar(id, 2, HOJE.plusDays(2), null, "Ana");
        Funil.Oportunidade segundo = funil.buscar(id).orElseThrow();
        assertThat(segundo.toque()).isEqualTo(2);
        assertThat(segundo.proximaAcao()).isEqualTo(HOJE.plusDays(2));

        funil.avancar(id, 2, null, HOJE.plusDays(1), "Ana");
        Funil.Oportunidade encerrando = funil.buscar(id).orElseThrow();
        assertThat(encerrando.proximaAcao()).isNull();
        assertThat(encerrando.encerraEm()).isEqualTo(HOJE.plusDays(1));
        assertThat(funil.fecharEncerradas(HOJE)).isZero();

        assertThat(funil.fecharEncerradas(HOJE.plusDays(1))).isEqualTo(1);
        Funil.Oportunidade perdida = funil.buscar(id).orElseThrow();
        assertThat(perdida.etapa()).isEqualTo(Etapa.PERDIDO);
        assertThat(perdida.motivoPerda()).isEqualTo(Funil.MotivoPerda.SEM_RESPOSTA);
        assertThat(perdida.encerraEm()).isNull();
        // Abertura, envio, toque 2, encerramento e perda.
        assertThat(eventos(id)).isEqualTo(5);
    }

    @Test
    void mudarDeEtapaDesfazOEncerramento() {
        long id = funil.abrir(CONTA, "c9", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        funil.mudar(id, Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null, HOJE, 1, "Ana", "Ana");
        funil.avancar(id, 1, null, HOJE.plusDays(1), "Ana");

        funil.mudar(id, Etapa.ORCAMENTO_ENVIADO, Etapa.EM_NEGOCIACAO, null, HOJE.plusDays(1), 1, null, "mandalore");

        assertThat(funil.buscar(id).orElseThrow().encerraEm()).isNull();
    }

    @Test
    void resumoContaRetornosDoDiaEAtrasadosPorResponsavel() {
        long hoje = funil.abrir(CONTA, "c10", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        long ana = funil.abrir(CONTA, "c11", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE.minusDays(2), "mandalore");
        funil.abrir(CONTA, "c12", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE.minusDays(1), "mandalore");
        funil.contato(ana, null, "Ana");

        Funil.ResumoRetornos resumo = funil.resumoRetornos(HOJE);

        assertThat(hoje).isPositive();
        assertThat(resumo.doDia()).isGreaterThanOrEqualTo(1);
        assertThat(resumo.atrasadosPorResponsavel().get("Ana")).isGreaterThanOrEqualTo(1L);
        assertThat(resumo.atrasadosPorResponsavel()).containsKey("");
    }

    @Test
    void agendamentoPeloAssistenteDepoisDaAberturaFechaComoAgendado() {
        long antes = funil.abrir(CONTA, "it-funil-1", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        long sem = funil.abrir(CONTA, "it-funil-2", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        jdbc.update("""
                INSERT INTO observability.flow_event (conversation_id, component, stage, status, event_name, details)
                VALUES ('it-funil-1', 'MANDALORE', 'fulfillment.acao.agenda.agendar', 'SUCCESS',
                        'RESULTADO_SUCESSO_ESCRITA', '{"escrita": "agendar"}'::jsonb)
                """, new MapSqlParameterSource());

        assertThat(funil.fecharAgendadasPelaTrilha()).isEqualTo(1);

        assertThat(funil.buscar(antes).orElseThrow().etapa()).isEqualTo(Etapa.AGENDADO);
        assertThat(funil.buscar(sem).orElseThrow().etapa()).isEqualTo(Etapa.NOVO);
        assertThat(funil.fecharAgendadasPelaTrilha()).isZero();
    }

    @Test
    void semRespostaDesdeOLimiteFechaComoPerdido() {
        long parada = funil.abrir(CONTA, "c3", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        long ativa = funil.abrir(CONTA, "c4", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        jdbc.update("UPDATE funil.oportunidade SET ultima_resposta_em = now() - interval '20 days' WHERE id = :id",
                new MapSqlParameterSource("id", parada));

        int fechadas = funil.fecharSemResposta(Instant.now().minusSeconds(15 * 86_400));

        assertThat(fechadas).isGreaterThanOrEqualTo(1);
        assertThat(funil.buscar(parada).orElseThrow().motivoPerda()).isEqualTo(Funil.MotivoPerda.SEM_RESPOSTA);
        assertThat(funil.buscar(ativa).orElseThrow().etapa()).isEqualTo(Etapa.NOVO);
    }

    @Test
    void retornoAvisadoSaiDaListaDoDia() {
        long id = funil.abrir(CONTA, "c5", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE.minusDays(1), "mandalore");
        funil.abrir(CONTA, "c6", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE.plusDays(1), "mandalore");

        assertThat(funil.retornosPendentes(HOJE)).filteredOn(o -> CONTA.equals(o.conta()))
                .extracting(Funil.Oportunidade::id).containsExactly(id);
        funil.retornoAvisado(id, HOJE);
        assertThat(funil.retornosPendentes(HOJE)).filteredOn(o -> CONTA.equals(o.conta())).isEmpty();
        assertThat(funil.listar(Set.of(CONTA), Instant.now().minusSeconds(86_400))).hasSize(2);
        assertThat(funil.listar(Set.of("outra-conta"), Instant.now().minusSeconds(86_400))).isEmpty();
    }

    @Test
    void telaListaEMudaAEtapaComOLoginDoFunil() throws Exception {
        long id = funil.abrir(CONTA, "c7", Funil.Origem.ORCAMENTO_FOTO, new BigDecimal("25"), 1, HOJE, "mandalore");

        mvc.perform(get("/funil/api/oportunidades")).andExpect(status().isUnauthorized());
        mvc.perform(get("/funil/api/oportunidades").header("Authorization", LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hoje").value(HOJE.toString()))
                .andExpect(jsonPath("$.regras[?(@.conta == '%s')].regras.cadencia[0]".formatted(CONTA)).value(1))
                .andExpect(jsonPath("$.diasSemResposta").value(30))
                .andExpect(jsonPath("$.oportunidades[?(@.id == %d)].valor".formatted(id)).value(25.0));
        mvc.perform(post("/funil/api/oportunidades/{id}/etapa", id).header("Authorization", LOGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"etapa\":\"PERDIDO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Informe o motivo da perda"));
        mvc.perform(post("/funil/api/oportunidades/{id}/etapa", id).header("Authorization", LOGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"etapa\":\"AGENDADO\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/funil/api/oportunidades/{id}/retorno", id).header("Authorization", LOGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"" + HOJE.plusDays(1) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/curadoria/api/conversas").header("Authorization", LOGIN)).andExpect(status().isForbidden());

        assertThat(funil.buscar(id).orElseThrow().etapa()).isEqualTo(Etapa.AGENDADO);
    }

    @Test
    void regrasDaClinicaFicamGravadasComHistorico() {
        Funil.Regras primeira = new Funil.Regras(List.of(1, 3), 2, false, Set.of());
        Funil.Regras segunda = new Funil.Regras(List.of(2), 4, true, Set.of(LocalDate.of(2026, 12, 8)));

        regras.salvar(CONTA_REGRAS, primeira, "gestora@clinica");
        regras.salvar(CONTA_REGRAS, segunda, "gestora@clinica");

        assertThat(regras.salvas().get(CONTA_REGRAS)).satisfies(s -> {
            assertThat(s.regras()).isEqualTo(segunda);
            assertThat(s.atualizadaPor()).isEqualTo("gestora@clinica");
        });
        assertThat(jdbc.queryForList("SELECT anterior FROM funil.regra_evento WHERE conta_id = :conta ORDER BY id",
                new MapSqlParameterSource("conta", CONTA_REGRAS), String.class))
                .containsExactly(null, PostgresRegrasFunilAdapter.descrever(primeira));
    }

    @Test
    void soOGestorAlteraAsRegrasPelaTela() throws Exception {
        mvc.perform(put("/funil/api/regras/{conta}", CONTA_REGRAS).header("Authorization", LOGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cadencia\":[1],\"diasEncerramento\":2}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/funil/api/oportunidades").header("Authorization", LOGIN))
                .andExpect(jsonPath("$.podeEditarRegras").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM funil.regra WHERE conta_id = :conta",
                new MapSqlParameterSource("conta", CONTA_REGRAS), Long.class)).isZero();
    }

    private long eventos(long id) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM funil.evento WHERE oportunidade_id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        return total == null ? 0 : total;
    }
}
