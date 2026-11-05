package br.com.mandalore.agendamentoconversa.adapter.in.web.precos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Login do financeiro na tela da tabela de preços, separado da curadoria. */
@Configuration
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PrecosWebConfig {

    @Bean
    public FilterRegistrationBean<PrecosAuthFilter> precosAuthFilter(PrecosAcesso acesso) {
        FilterRegistrationBean<PrecosAuthFilter> registro = new FilterRegistrationBean<>(new PrecosAuthFilter(acesso));
        registro.addUrlPatterns("/precos", "/precos/*");
        registro.setName("precosAuthFilter");
        return registro;
    }
}
