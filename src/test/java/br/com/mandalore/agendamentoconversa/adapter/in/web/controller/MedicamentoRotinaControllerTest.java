package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.MedicamentoRotinaUseCase;

class MedicamentoRotinaControllerTest {

    private static final AdminAcesso.Login GESTOR = new AdminAcesso.Login("gestor@clinica", Set.of(Modulo.MEDICAMENTOS_ROTINA),
            Set.of("7"), 10L, 0, false, "Gestor");
    private static final Atendimento.MedicamentoRotina DIPIRONA =
            new Atendimento.MedicamentoRotina(1L, "Dipirona", "500 mg comprimido", "1 cp de 6/6 horas", "Oral", false);

    private MedicamentoRotinaUseCase medicamentos;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        medicamentos = mock(MedicamentoRotinaUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new MedicamentoRotinaController(medicamentos, "1"))
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .build();
    }

    @Test
    void contextoUsaAContaDoLoginETrazOsItens() throws Exception {
        when(medicamentos.listar("7")).thenReturn(List.of(DIPIRONA));

        mvc.perform(get("/consultorio/medicamentos/api/contexto").requestAttr(AdminAuthFilter.LOGIN, GESTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conta").value("7"))
                .andExpect(jsonPath("$.contas[0]").value("7"))
                .andExpect(jsonPath("$.itens[0].posologia").value("1 cp de 6/6 horas"));
    }

    @Test
    void contaDeOutraClinicaDevolve403() throws Exception {
        mvc.perform(get("/consultorio/api/medicamentos-rotina").param("conta", "8").requestAttr(AdminAuthFilter.LOGIN, GESTOR))
                .andExpect(status().isForbidden());

        verify(medicamentos, never()).listar(anyString());
    }

    @Test
    void salvarRepetidoDevolve409ComMensagem() throws Exception {
        when(medicamentos.salvar(any(), any(), any())).thenReturn(Atendimento.Operacao.falha(CodigoNegocio.MEDICAMENTO_DUPLICADO));

        mvc.perform(put("/consultorio/medicamentos/api/itens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicamento\":\"Dipirona\",\"apresentacao\":\"500 mg comprimido\"}")
                        .requestAttr(AdminAuthFilter.LOGIN, GESTOR))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("MEDICAMENTO_DUPLICADO"))
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    void salvarSemMedicamentoDevolve400() throws Exception {
        when(medicamentos.salvar(any(), any(), any())).thenThrow(new IllegalArgumentException("Informe o medicamento"));

        mvc.perform(put("/consultorio/medicamentos/api/itens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr(AdminAuthFilter.LOGIN, GESTOR))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Informe o medicamento"));
    }

    @Test
    void excluirInexistenteDevolve404ERestaurarDevolveQuantos() throws Exception {
        when(medicamentos.restaurarPadrao("7", "gestor@clinica")).thenReturn(4);

        mvc.perform(delete("/consultorio/medicamentos/api/itens/99").requestAttr(AdminAuthFilter.LOGIN, GESTOR))
                .andExpect(status().isNotFound());
        mvc.perform(post("/consultorio/medicamentos/api/restaurar").requestAttr(AdminAuthFilter.LOGIN, GESTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incluidos").value(4));
    }
}
