package br.com.mandalore.agendamentoconversa.adapter.in.web.curadoria;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Login do financeiro na tela da tabela de preços, separado da curadoria. */
@Configuration
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PrecosWebConfig {

    @Bean
    public FilterRegistrationBean<CuradoriaAuthFilter> precosAuthFilter(
            @Value("${precos.user:}") String usuario,
            @Value("${precos.password:}") String senha
    ) {
        FilterRegistrationBean<CuradoriaAuthFilter> registro = new FilterRegistrationBean<>(
                new CuradoriaAuthFilter(usuario, senha, "Tabela de precos", "PRECOS_USER e PRECOS_PASSWORD"));
        registro.addUrlPatterns("/precos", "/precos/*");
        registro.setName("precosAuthFilter");
        return registro;
    }
}
