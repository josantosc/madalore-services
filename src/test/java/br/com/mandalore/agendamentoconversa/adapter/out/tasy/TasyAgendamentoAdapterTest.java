package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;

class TasyAgendamentoAdapterTest {

    private NamedParameterJdbcTemplate jdbc;
    private TasyAgendamentoAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        adapter = new TasyAgendamentoAdapter(jdbc, "MANDALORE");
    }

    @Test
    void reservaHorarioLivreComConvenioEscolhido() {
        when(jdbc.update(contains("UPDATE AGENDA_CONSULTA"), any(SqlParameterSource.class))).thenReturn(1);

        assertThat(adapter.agendar(pedido("k1")).codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        verify(jdbc).update(contains("IE_STATUS_AGENDA = 'L'"), argThat((SqlParameterSource p) ->
                new BigDecimal("15").equals(p.getValue("convenio"))
                        && "1001".equals(p.getValue("paciente"))
                        && "MANDALORE".equals(p.getValue("usuario"))));
    }

    @Test
    void horarioOcupadoPorOutroPacienteEhIndisponivel() {
        when(jdbc.update(contains("UPDATE AGENDA_CONSULTA"), any(SqlParameterSource.class))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT COUNT"), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(0);

        assertThat(adapter.agendar(pedido("k2")).codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
    }

    @Test
    void horarioJaDoMesmoPacienteContaComoSucesso() {
        when(jdbc.update(contains("UPDATE AGENDA_CONSULTA"), any(SqlParameterSource.class))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT COUNT"), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(1);

        assertThat(adapter.agendar(pedido("k3")).codigo()).isEqualTo(CodigoNegocio.SUCESSO);
    }

    @Test
    void repeticaoDaMesmaChaveNaoGravaDeNovo() {
        when(jdbc.update(contains("UPDATE AGENDA_CONSULTA"), any(SqlParameterSource.class))).thenReturn(1);

        adapter.agendar(pedido("k4"));
        assertThat(adapter.agendar(pedido("k4")).codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        verify(jdbc, times(1)).update(any(String.class), any(SqlParameterSource.class));
    }

    @Test
    void cancelamentoMarcaComoCanceladoSemLiberarHorario() {
        when(jdbc.update(contains("IE_STATUS_AGENDA = 'C'"), any(SqlParameterSource.class))).thenReturn(1);

        assertThat(adapter.cancelar("c1", "1001", "6001").codigo()).isEqualTo(CodigoNegocio.SUCESSO);

        verify(jdbc, never()).update(contains("IE_STATUS_AGENDA = 'L',"), any(SqlParameterSource.class));
    }

    @Test
    void cancelamentoRepetidoDeAgendamentoJaCanceladoEhSucesso() {
        when(jdbc.update(contains("IE_STATUS_AGENDA = 'C'"), any(SqlParameterSource.class))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT COUNT"), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(1);

        assertThat(adapter.cancelar("c2", "1001", "6001").codigo()).isEqualTo(CodigoNegocio.SUCESSO);
    }

    @Test
    void confirmacaoDeAgendamentoInexistente() {
        when(jdbc.update(contains("'CN'"), any(SqlParameterSource.class))).thenReturn(0);

        assertThat(adapter.confirmar("f1", "1001", "6001").codigo()).isEqualTo(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
    }

    private static PedidoAgendar pedido(String chave) {
        return new PedidoAgendar(
                chave,
                "1001",
                "3001",
                LocalDateTime.of(2026, 11, 10, 9, 0),
                "52998224725",
                LocalDate.of(1990, 5, 20),
                "Paciente Teste",
                false,
                null,
                "15"
        );
    }
}
