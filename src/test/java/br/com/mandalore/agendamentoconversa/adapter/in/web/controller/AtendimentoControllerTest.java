package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAcesso;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAuthFilter;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.Modulo;
import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;

class AtendimentoControllerTest {

    private static final AdminAcesso.Login RECEPCAO = new AdminAcesso.Login("recepcao@clinica", Set.of(Modulo.ATENDIMENTO),
            Set.of("7"), 10L, 0, false, "Recepção");

    private AtendimentoUseCase atendimento;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        atendimento = mock(AtendimentoUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new AtendimentoController(atendimento, "1"))
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .build();
    }

    @Test
    void filaUsaAContaDoLoginQuandoNaoInformada() throws Exception {
        LocalDate dia = LocalDate.of(2026, 11, 11);
        when(atendimento.fila("7", dia)).thenReturn(List.of(
                new Atendimento.FilaItem(5L, null, null, "Maria", "Dr. João", "Clínica geral", "CONFIRMADO", null, null)));

        mvc.perform(get("/atendimento/api/fila").param("data", "2026-11-11").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agendamentoId").value(5))
                .andExpect(jsonPath("$[0].paciente").value("Maria"))
                .andExpect(jsonPath("$[0].status").value("CONFIRMADO"));
    }

    @Test
    void contaDeOutraClinicaDevolve403() throws Exception {
        mvc.perform(get("/atendimento/api/fila").param("conta", "8").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erro").exists());

        verify(atendimento, never()).fila(anyString(), any());
    }

    @Test
    void chegadaComSucessoDevolveOAtendimento() throws Exception {
        when(atendimento.registrarChegada("7", 5L, "recepcao@clinica")).thenReturn(Atendimento.Operacao.sucesso(99L));

        mvc.perform(post("/atendimento/api/agendamentos/5/chegada").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("SUCESSO"))
                .andExpect(jsonPath("$.atendimentoId").value(99));
    }

    @Test
    void chegadaForaDoDiaDevolve409() throws Exception {
        when(atendimento.registrarChegada("7", 5L, "recepcao@clinica"))
                .thenReturn(Atendimento.Operacao.falha(CodigoNegocio.CHEGADA_FORA_DO_DIA));

        mvc.perform(post("/atendimento/api/agendamentos/5/chegada").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CHEGADA_FORA_DO_DIA"))
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    void desfazerSemMotivoDevolve400() throws Exception {
        when(atendimento.desfazerChegada("7", 99L, null, "recepcao@clinica"))
                .thenThrow(new IllegalArgumentException("Informe o motivo para desfazer a chegada"));

        mvc.perform(post("/atendimento/api/atendimentos/99/desfazer-chegada")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Informe o motivo para desfazer a chegada"));
    }

    @Test
    void desfazerDepoisDeIniciadaDevolve409() throws Exception {
        when(atendimento.desfazerChegada("7", 99L, "engano", "recepcao@clinica"))
                .thenReturn(Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_EM_ANDAMENTO));

        mvc.perform(post("/atendimento/api/atendimentos/99/desfazer-chegada")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"engano\"}")
                        .requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ATENDIMENTO_EM_ANDAMENTO"));
    }

    @Test
    void eventosSemConteudoClinico() throws Exception {
        when(atendimento.eventos("7", 99L)).thenReturn(List.of(new Atendimento.Evento("CHEGADA", "recepcao@clinica", null, null)));

        mvc.perform(get("/atendimento/api/atendimentos/99/eventos").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tipo").value("CHEGADA"));
    }

    @Test
    void contaNaoHabilitadaDevolve404() throws Exception {
        when(atendimento.registrarChegada(anyString(), anyLong(), anyString())).thenThrow(new AtendimentoDesabilitadoException());

        mvc.perform(post("/atendimento/api/agendamentos/5/chegada").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isNotFound());
    }
}
