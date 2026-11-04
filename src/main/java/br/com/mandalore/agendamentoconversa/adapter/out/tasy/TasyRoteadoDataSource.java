package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import com.zaxxer.hikari.HikariDataSource;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;

/**
 * Escolhe o pool do Tasy pela conta do Chatwoot da requisição.
 * Conta sem Oracle próprio cai no pool global; sem pool global, a consulta falha em vez de ler outro hospital.
 */
class TasyRoteadoDataSource extends AbstractRoutingDataSource implements AutoCloseable {

    private final List<HikariDataSource> pools = new ArrayList<>();

    TasyRoteadoDataSource(HikariDataSource padrao, Map<String, HikariDataSource> porCliente) {
        Map<Object, Object> alvos = new HashMap<>(porCliente);
        setTargetDataSources(alvos);
        pools.addAll(porCliente.values());
        if (padrao != null) {
            setDefaultTargetDataSource(padrao);
            pools.add(padrao);
        }
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return ClienteAtual.accountId();
    }

    DataSource alvo() {
        return determineTargetDataSource();
    }

    @Override
    public void close() {
        pools.forEach(HikariDataSource::close);
    }
}
