package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAcesso;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAuthFilter;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.Modulo;
import br.com.mandalore.agendamentoconversa.application.domain.exception.AcessoNegadoException;
import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.Contexto;

class ConsultorioControllerTest {

    private static final AdminAcesso.Login MEDICO = new AdminAcesso.Login("medico@clinica",
            Set.of(Modulo.ATENDIMENTO, Modulo.CONSULTORIO), Set.of("7"), 42L, 0, false, "Dr. João");
    private static final Contexto CTX = new Contexto("7", 42L, "medico@clinica");

    private ConsultorioUseCase consultorio;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        consultorio = mock(ConsultorioUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ConsultorioController(consultorio, "1"))
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON, MediaType.TEXT_HTML))
                .build();
    }

    @Test
    void iniciarComSucesso() throws Exception {
        when(consultorio.iniciarConsulta(CTX, 99L)).thenReturn(Atendimento.Operacao.sucesso(99L));

        mvc.perform(post("/consultorio/api/atendimentos/99/iniciar").requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("SUCESSO"))
                .andExpect(jsonPath("$.id").value(99));
    }

    @Test
    void profissionalJaEmConsultaDevolve409() throws Exception {
        when(consultorio.iniciarConsulta(CTX, 99L)).thenReturn(Atendimento.Operacao.falha(CodigoNegocio.PROFISSIONAL_EM_CONSULTA));

        mvc.perform(post("/consultorio/api/atendimentos/99/iniciar").requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PROFISSIONAL_EM_CONSULTA"))
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    void atendimentoDeOutroProfissionalDevolve403() throws Exception {
        when(consultorio.iniciarConsulta(CTX, 99L)).thenThrow(new AcessoNegadoException("Este atendimento é de outro profissional"));

        mvc.perform(post("/consultorio/api/atendimentos/99/iniciar").requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erro").value("Este atendimento é de outro profissional"));
    }

    @Test
    void medicoSemVinculoRecebeMensagem403() throws Exception {
        when(consultorio.fila(eq(CTX), any())).thenThrow(new AcessoNegadoException("Seu usuário não está vinculado a um profissional"));

        mvc.perform(get("/consultorio/api/fila").requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erro").value("Seu usuário não está vinculado a um profissional"));
    }

    @Test
    void loginSemCadastroNoBancoNaoTemProfissional() throws Exception {
        AdminAcesso.Login variavel = new AdminAcesso.Login("admin", Set.of(Modulo.CONSULTORIO));

        mvc.perform(get("/consultorio/api/fila").requestAttr(AdminAuthFilter.LOGIN, variavel))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erro").value("Seu usuário não está vinculado a um profissional"));

        verify(consultorio, never()).fila(any(), any());
    }

    @Test
    void contaDeOutraClinicaDevolve403() throws Exception {
        mvc.perform(post("/consultorio/api/atendimentos/99/iniciar").param("conta", "8").requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isForbidden());

        verify(consultorio, never()).iniciarConsulta(any(), anyLong());
    }

    @Test
    void prontuarioRepassaIpESemCache() throws Exception {
        when(consultorio.prontuario(CTX, 99L, "203.0.113.9"))
                .thenReturn(new Atendimento.Prontuario(null, null, null, null, null, null));

        mvc.perform(get("/consultorio/api/atendimentos/99")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(consultorio).prontuario(CTX, 99L, "203.0.113.9");
    }

    @Test
    void anamneseLiberadaNaoSeEditaDevolve409() throws Exception {
        when(consultorio.salvarRascunhoAnamnese(eq(CTX), eq(99L), any()))
                .thenReturn(Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO));

        mvc.perform(put("/consultorio/api/atendimentos/99/anamnese")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queixaPrincipal\":\"dor\"}")
                        .requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("REGISTRO_JA_LIBERADO"));
    }

    @Test
    void cidInvalidoDevolve400() throws Exception {
        when(consultorio.incluirDiagnostico(eq(CTX), eq(99L), any()))
                .thenThrow(new IllegalArgumentException("CID inválido. Use o formato J06 ou J06.9"));

        mvc.perform(post("/consultorio/api/atendimentos/99/diagnosticos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cid\":\"XYZ\",\"tipo\":\"PRINCIPAL\"}")
                        .requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("CID inválido. Use o formato J06 ou J06.9"));
    }

    @Test
    void inativarRepassaJustificativa() throws Exception {
        when(consultorio.inativarEvolucao(CTX, 3L, "erro de digitação")).thenReturn(Atendimento.Operacao.sucesso(3L));

        mvc.perform(post("/consultorio/api/evolucoes/3/inativar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justificativa\":\"erro de digitação\"}")
                        .requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("SUCESSO"));
    }

    @Test
    void impressaoDevolveHtml() throws Exception {
        when(consultorio.impressaoReceita(eq(CTX), eq(5L), any()))
                .thenReturn(new ConsultorioUseCase.Impressao("<html><body>Receita</body></html>"));

        mvc.perform(get("/consultorio/api/receitas/5/impressao").requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("Receita")));
    }

    @Test
    void finalizarComRascunhoDevolve409() throws Exception {
        when(consultorio.finalizar(eq(CTX), eq(99L), any()))
                .thenReturn(Atendimento.Operacao.falha(CodigoNegocio.RASCUNHO_PENDENTE));

        mvc.perform(post("/consultorio/api/atendimentos/99/finalizar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoAlta\":\"ALTA_MEDICA\"}")
                        .requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RASCUNHO_PENDENTE"));
    }

    @Test
    void paginaDoConsultorio() throws Exception {
        mvc.perform(get("/consultorio"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(content().string(containsString("telaFila")))
                .andExpect(content().string(containsString("/consultorio/api/fila")));
    }

    @Test
    void contaNaoHabilitadaDevolve404() throws Exception {
        when(consultorio.fila(eq(CTX), any())).thenThrow(new AtendimentoDesabilitadoException());

        mvc.perform(get("/consultorio/api/fila").requestAttr(AdminAuthFilter.LOGIN, MEDICO))
                .andExpect(status().isNotFound());
    }
}
