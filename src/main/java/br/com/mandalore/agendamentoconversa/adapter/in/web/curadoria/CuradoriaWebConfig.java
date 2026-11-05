package br.com.mandalore.agendamentoconversa.adapter.in.web.curadoria;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "curadoria.enabled", havingValue = "true")
public class CuradoriaWebConfig {

    @Bean
    public FilterRegistrationBean<CuradoriaAuthFilter> curadoriaAuthFilter(
            @Value("${curadoria.user:}") String usuario,
            @Value("${curadoria.password:}") String senha
    ) {
        FilterRegistrationBean<CuradoriaAuthFilter> registro =
                new FilterRegistrationBean<>(new CuradoriaAuthFilter(usuario, senha));
        registro.addUrlPatterns("/curadoria", "/curadoria/*");
        registro.setName("curadoriaAuthFilter");
        return registro;
    }
}
