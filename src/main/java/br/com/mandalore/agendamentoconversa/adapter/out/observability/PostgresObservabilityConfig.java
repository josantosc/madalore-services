package br.com.mandalore.agendamentoconversa.adapter.out.observability;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
@ConditionalOnProperty(name = "observability.postgres.enabled", havingValue = "true")
public class PostgresObservabilityConfig {

    @Bean(name = "observabilityDataSource", destroyMethod = "close")
    @ConfigurationProperties("observability.datasource.hikari")
    public DataSource observabilityDataSource(
            @org.springframework.beans.factory.annotation.Value("${observability.datasource.url}") String url,
            @org.springframework.beans.factory.annotation.Value("${observability.datasource.username}") String username,
            @org.springframework.beans.factory.annotation.Value("${observability.datasource.password}") String password
    ) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url)
                .username(username)
                .password(password)
                .build();
        dataSource.setPoolName("mandalore-observability");
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    @Bean(name = "observabilityJdbcTemplate")
    public NamedParameterJdbcTemplate observabilityJdbcTemplate(
            @Qualifier("observabilityDataSource") DataSource dataSource
    ) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().setQueryTimeout(2);
        return jdbc;
    }

    @Bean
    @ConditionalOnProperty(
            name = "observability.postgres.initialize-schema",
            havingValue = "true"
    )
    public DataSourceInitializer observabilityDataSourceInitializer(
            @Qualifier("observabilityDataSource") DataSource dataSource
    ) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/observability/schema.sql"),
                new ClassPathResource("db/orcamento/schema.sql"),
                new ClassPathResource("db/agenda/schema.sql"),
                new ClassPathResource("db/funil/schema.sql"),
                new ClassPathResource("db/acesso/schema.sql"),
                new ClassPathResource("db/atendimento/schema.sql")
        );
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
