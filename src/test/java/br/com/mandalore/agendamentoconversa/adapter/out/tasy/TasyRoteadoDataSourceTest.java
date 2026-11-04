package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;

class TasyRoteadoDataSourceTest {

    @Test
    void contaComOracleProprioUsaOPoolDela() {
        HikariDataSource padrao = new HikariDataSource();
        HikariDataSource hospital = new HikariDataSource();
        try (TasyRoteadoDataSource roteado = new TasyRoteadoDataSource(padrao, Map.of("2", hospital))) {
            DataSource daConta2 = ClienteAtual.executar("2", roteado::alvo);
            DataSource daConta7 = ClienteAtual.executar("7", roteado::alvo);
            assertThat(daConta2).isSameAs(hospital);
            assertThat(daConta7).isSameAs(padrao);
            assertThat(roteado.alvo()).isSameAs(padrao);
        }
    }

    @Test
    void semPoolGlobalContaDesconhecidaFalhaEmVezDeLerOutroHospital() {
        HikariDataSource hospital = new HikariDataSource();
        try (TasyRoteadoDataSource roteado = new TasyRoteadoDataSource(null, Map.of("2", hospital))) {
            assertThatThrownBy(() -> ClienteAtual.executar("7", roteado::alvo))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void contextoEhLimpoDepoisDaRequisicao() {
        ClienteAtual.executar("2", () -> "ok");

        assertThat(ClienteAtual.accountId()).isNull();
    }
}
