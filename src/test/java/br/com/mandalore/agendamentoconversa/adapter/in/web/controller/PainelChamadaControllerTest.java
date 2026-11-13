package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;

class PainelChamadaControllerTest {

    private static final String CHAVE = "chave-da-tv-1234567890";

    private AtendimentoUseCase atendimento;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        atendimento = mock(AtendimentoUseCase.class);
        MockEnvironment ambiente = new MockEnvironment()
                .withProperty("mandalore.clientes.7.painel.chave", CHAVE)
                .withProperty("mandalore.clientes.7.nome", "Clínica Bio")
                .withProperty("mandalore.clientes.8.painel.chave", "curta");
        mvc = MockMvcBuilders.standaloneSetup(new PainelChamadaController(atendimento, ambiente, "1", null, null)).build();
    }

    @Test
    void chaveCertaDevolveChamadasComNomeDaClinicaSemRastro() throws Exception {
        when(atendimento.chamadasDoPainel("7")).thenReturn(List.of(
                new Atendimento.Chamada("Maria S. O.", "Dra. Ana", "Consultório 3", LocalDateTime.of(2026, 11, 13, 9, 30))));

        mvc.perform(get("/atendimento/painel/api/chamadas").param("conta", "7").param("chave", CHAVE))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.clinica").value("Clínica Bio"))
                .andExpect(jsonPath("$.chamadas[0].paciente").value("Maria S. O."))
                .andExpect(jsonPath("$.chamadas[0].profissional").value("Dra. Ana"))
                .andExpect(jsonPath("$.chamadas[0].local").value("Consultório 3"));
    }

    @Test
    void chaveErradaAusenteOuCurtaDevolve404SemConsultar() throws Exception {
        mvc.perform(get("/atendimento/painel/api/chamadas").param("conta", "7").param("chave", "errada"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/atendimento/painel/api/chamadas").param("conta", "7"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/atendimento/painel/api/chamadas").param("conta", "8").param("chave", "curta"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/atendimento/painel/api/chamadas").param("conta", "9").param("chave", CHAVE))
                .andExpect(status().isNotFound());
        mvc.perform(get("/atendimento/painel").param("conta", "7").param("chave", "errada"))
                .andExpect(status().isNotFound());

        verify(atendimento, never()).chamadasDoPainel(anyString());
    }

    @Test
    void paginaAbreComChaveCerta() throws Exception {
        mvc.perform(get("/atendimento/painel").param("conta", "7").param("chave", CHAVE))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
    }
}
