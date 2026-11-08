package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.zaxxer.hikari.HikariDataSource;

class TasyTimeoutPropertiesTest {

    @Test
    void applicationPropertiesLimitaConexaoELeituraDoOracle() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> fonte : new PropertiesPropertySourceLoader()
                .load("application", new ClassPathResource("application.properties"))) {
            environment.getPropertySources().addLast(fonte);
        }
        try (HikariDataSource dataSource = new HikariDataSource()) {
            Binder.get(environment).bind("tasy.datasource.hikari", Bindable.ofInstance(dataSource));

            assertThat(dataSource.getConnectionTimeout()).isEqualTo(1500);
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry("oracle.net.CONNECT_TIMEOUT", "1500")
                    .containsEntry("oracle.jdbc.ReadTimeout", "10000");
        }
    }
}
