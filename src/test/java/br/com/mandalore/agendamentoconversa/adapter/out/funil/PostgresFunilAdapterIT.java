package br.com.mandalore.agendamentoconversa.adapter.out.funil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
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
    private static final String LOGIN = "Basic " + Base64.getEncoder().encodeToString("f:p".getBytes(StandardCharsets.UTF_8));
    private static final LocalDate HOJE = LocalDate.now();

    @Autowired
    private PostgresFunilAdapter funil;

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

        assertThat(funil.mudar(id, Etapa.NOVO, Etapa.ORCAMENTO_ENVIADO, null, HOJE.plusDays(2), "Ana", "Ana")).isTrue();
        assertThat(funil.mudar(id, Etapa.NOVO, Etapa.AGENDADO, null, null, null, "outro")).isFalse();
        assertThat(funil.mudar(id, Etapa.ORCAMENTO_ENVIADO, Etapa.PERDIDO, Funil.MotivoPerda.PRECO, HOJE, null, "ana")).isTrue();

        Funil.Oportunidade perdida = funil.buscar(id).orElseThrow();
        assertThat(perdida.etapa()).isEqualTo(Etapa.PERDIDO);
        assertThat(perdida.motivoPerda()).isEqualTo(Funil.MotivoPerda.PRECO);
        assertThat(perdida.responsavel()).isEqualTo("Ana");
        assertThat(perdida.proximaAcao()).isNull();
        assertThat(perdida.fechadaEm()).isNotNull();
        assertThat(funil.aberta(CONTA, "c2")).isEmpty();

        long nova = funil.abrir(CONTA, "c2", Funil.Origem.ORCAMENTO_MANUAL, null, null, HOJE, "mandalore");
        assertThat(nova).isNotEqualTo(id);
        assertThatThrownBy(() -> funil.mudar(id, Etapa.PERDIDO, Etapa.EM_NEGOCIACAO, null, HOJE, null, "ana"))
                .isInstanceOf(IllegalArgumentException.class);
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

    private long eventos(long id) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM funil.evento WHERE oportunidade_id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        return total == null ? 0 : total;
    }
}
