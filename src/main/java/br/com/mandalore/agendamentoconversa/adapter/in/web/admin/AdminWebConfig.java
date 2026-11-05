package br.com.mandalore.agendamentoconversa.adapter.in.web.admin;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminWebConfig {

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(AdminAcesso acesso) {
        FilterRegistrationBean<AdminAuthFilter> registro = new FilterRegistrationBean<>(new AdminAuthFilter(acesso));
        registro.addUrlPatterns(AdminAuthFilter.CAMINHOS);
        registro.setName("adminAuthFilter");
        return registro;
    }
}
