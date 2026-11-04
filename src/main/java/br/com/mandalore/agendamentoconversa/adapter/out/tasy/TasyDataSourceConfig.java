package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.adapter.cliente.TasyConfigurado;

@Configuration
@Conditional(TasyConfigurado.class)
public class TasyDataSourceConfig {

    private static final String POOL = "agendamento-conversa";

    @Bean
    @Primary
    public DataSource tasyDataSource(
            Environment environment,
            ClientesProperties clientes,
            @Value("${tasy.datasource.url:}") String url,
            @Value("${tasy.datasource.username:}") String username,
            @Value("${tasy.datasource.password:}") String password,
            @Value("${tasy.datasource.driver-class-name:oracle.jdbc.OracleDriver}") String driver,
            @Value("${tasy.datasource.oracle-net-encryption-enabled:true}") boolean oracleNetEncryptionEnabled
    ) {
        if (oracleNetEncryptionEnabled) {
            ensureOracleNetEncryptionSystemProperties();
        }
        Binder binder = Binder.get(environment);
        HikariDataSource padrao = url.isBlank()
                ? null
                : pool(binder, POOL, driver, url, username, password, oracleNetEncryptionEnabled);
        Map<String, HikariDataSource> porCliente = new LinkedHashMap<>();
        clientes.clientes().forEach((accountId, cliente) -> {
            ClientesProperties.Tasy tasy = cliente.tasy();
            if (tasy != null && tasy.configurado()) {
                porCliente.put(accountId, pool(binder, POOL + "-" + accountId, driver,
                        tasy.url(), tasy.username(), tasy.password(), oracleNetEncryptionEnabled));
            }
        });
        return new TasyRoteadoDataSource(padrao, porCliente);
    }

    @Bean
    public NamedParameterJdbcTemplate tasyJdbcTemplate(
            @Qualifier("tasyDataSource") DataSource tasyDataSource
    ) {
        return new NamedParameterJdbcTemplate(tasyDataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(
            @Qualifier("tasyDataSource") DataSource tasyDataSource
    ) {
        return new DataSourceTransactionManager(tasyDataSource);
    }

    private static HikariDataSource pool(
            Binder binder,
            String nome,
            String driver,
            String url,
            String username,
            String password,
            boolean oracleNetEncryptionEnabled
    ) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(driver)
                .url(url)
                .username(username)
                .password(password)
                .build();
        binder.bind("tasy.datasource.hikari", Bindable.ofInstance(dataSource));
        dataSource.setInitializationFailTimeout(-1);
        dataSource.setPoolName(nome);
        if (oracleNetEncryptionEnabled) {
            dataSource.addDataSourceProperty("oracle.net.encryption_client", "REQUIRED");
            dataSource.addDataSourceProperty("oracle.net.encryption_types_client", "(AES256)");
            dataSource.addDataSourceProperty("oracle.net.crypto_checksum_client", "REQUIRED");
            dataSource.addDataSourceProperty("oracle.net.crypto_checksum_types_client", "(SHA1)");
            dataSource.addDataSourceProperty("oracle.net.disableOob", "true");
        }
        return dataSource;
    }

    private static void ensureOracleNetEncryptionSystemProperties() {
        Map<String, String> propriedades = new HashMap<>();
        propriedades.put("oracle.net.encryption_client", "REQUIRED");
        propriedades.put("oracle.net.encryption_types_client", "(AES256)");
        propriedades.put("oracle.net.crypto_checksum_client", "REQUIRED");
        propriedades.put("oracle.net.crypto_checksum_types_client", "(SHA1)");
        propriedades.put("oracle.net.disableOob", "true");
        propriedades.forEach((chave, valor) -> {
            if (System.getProperty(chave) == null || System.getProperty(chave).isBlank()) {
                System.setProperty(chave, valor);
            }
        });
    }
}
