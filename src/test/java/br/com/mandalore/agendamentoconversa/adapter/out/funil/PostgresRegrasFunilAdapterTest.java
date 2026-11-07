package br.com.mandalore.agendamentoconversa.adapter.out.funil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;

class PostgresRegrasFunilAdapterTest {

    @Test
    void cadenciaInicialVemDoEnv() {
        PostgresRegrasFunilAdapter adapter = new PostgresRegrasFunilAdapter(mock(NamedParameterJdbcTemplate.class),
                mock(TransactionTemplate.class), Clock.systemUTC(), " 1, 3 ,5 ", 4, true);

        assertThat(adapter.padrao()).isEqualTo(new Funil.Regras(List.of(1, 3, 5), 4, true, Set.of()));
    }

    @Test
    void cadenciaInvalidaNoEnvImpedeASubida() {
        assertThatThrownBy(() -> new PostgresRegrasFunilAdapter(mock(NamedParameterJdbcTemplate.class),
                mock(TransactionTemplate.class), Clock.systemUTC(), "1,dois", 2, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FUNIL_CADENCIA");
        assertThatThrownBy(() -> new PostgresRegrasFunilAdapter(mock(NamedParameterJdbcTemplate.class),
                mock(TransactionTemplate.class), Clock.systemUTC(), "", 2, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1 a 10 contatos");
    }

    @Test
    void historicoDescreveARegraParaQuemAudita() {
        Funil.Regras regras = new Funil.Regras(List.of(1, 2), 3, false,
                Set.of(LocalDate.of(2026, 12, 8), LocalDate.of(2026, 1, 20)));

        assertThat(PostgresRegrasFunilAdapter.descrever(regras))
                .isEqualTo("contatos 1,2; encerra em 3; sábado não útil; feriados locais 2026-01-20,2026-12-08");
    }
}
