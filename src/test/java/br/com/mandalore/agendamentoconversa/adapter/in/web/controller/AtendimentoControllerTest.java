package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
import br.com.mandalore.agendamentoconversa.application.domain.port.in.CheckinUseCase;

class AtendimentoControllerTest {

    private static final AdminAcesso.Login RECEPCAO = new AdminAcesso.Login("recepcao@clinica", Set.of(Modulo.ATENDIMENTO),
            Set.of("7"), 10L, 0, false, "Recepção");

    private AtendimentoUseCase atendimento;
    private CheckinUseCase checkin;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        atendimento = mock(AtendimentoUseCase.class);
        checkin = mock(CheckinUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new AtendimentoController(atendimento, "1", checkin))
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .build();
    }

    @Test
    void checkinDaContaDoLoginDevolveQr() throws Exception {
        when(checkin.doDia("7")).thenReturn(Optional.of(new Atendimento.CheckinDoDia(LocalDate.of(2026, 11, 13),
                "ABC234", "https://wa.me/5547999990000?text=Cheguei%20ABC234")));

        mvc.perform(get("/atendimento/api/checkin").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.codigo").value("ABC234"))
                .andExpect(jsonPath("$.svg").value(org.hamcrest.Matchers.containsString("<path d=\"M")));
    }

    @Test
    void checkinSemConfiguracaoDevolve204EOutraContaEProibida() throws Exception {
        when(checkin.doDia("7")).thenReturn(Optional.empty());

        mvc.perform(get("/atendimento/api/checkin").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isNoContent());
        mvc.perform(get("/atendimento/api/checkin").param("conta", "8").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isForbidden());
        verify(checkin, never()).doDia("8");
    }

    @Test
    void filaUsaAContaDoLoginQuandoNaoInformada() throws Exception {
        LocalDate dia = LocalDate.of(2026, 11, 11);
        when(atendimento.fila("7", dia)).thenReturn(List.of(
                new Atendimento.FilaItem(5L, null, null, "Maria", "12345678901", "Dr. João", "Clínica geral", "CONFIRMADO", null, null, null, null, false)));

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
    void chamarRepassaOLocalEFuncionaSemCorpo() throws Exception {
        when(atendimento.chamar("7", 99L, "Financeiro", "recepcao@clinica")).thenReturn(Atendimento.Operacao.sucesso(99L));
        when(atendimento.chamar("7", 98L, null, "recepcao@clinica")).thenReturn(Atendimento.Operacao.sucesso(98L));

        mvc.perform(post("/atendimento/api/atendimentos/99/chamar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"local\":\"Financeiro\"}")
                        .requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atendimentoId").value(99));
        mvc.perform(post("/atendimento/api/atendimentos/98/chamar").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk());
    }

    @Test
    void locaisDaContaDoLogin() throws Exception {
        when(atendimento.locaisDeChamada("7")).thenReturn(List.of("Recepção", "Consultório 3"));

        mvc.perform(get("/atendimento/api/locais").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value("Consultório 3"));
    }

    @Test
    void contaNaoHabilitadaDevolve404() throws Exception {
        when(atendimento.registrarChegada(anyString(), anyLong(), anyString())).thenThrow(new AtendimentoDesabilitadoException());

        mvc.perform(post("/atendimento/api/agendamentos/5/chegada").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isNotFound());
    }

    @Test
    void pacientesPorCpfLeOCpfDoCorpoSemCache() throws Exception {
        when(atendimento.pacientesPorCpf("7", "123.456.789-01")).thenReturn(List.of(
                new Atendimento.PacienteResumo(77L, "Maria", "12345678901", LocalDate.of(1980, 1, 2), null)));

        mvc.perform(post("/atendimento/api/pacientes/cpf").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"123.456.789-01\"}").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].id").value(77));
    }

    @Test
    void fichaDoPacienteRepassaUsuarioEIp() throws Exception {
        Atendimento.PacienteResumo maria = new Atendimento.PacienteResumo(77L, "Maria", "12345678901", null, null);
        when(atendimento.fichaDoPaciente(eq("7"), eq(77L), eq("recepcao@clinica"), anyString()))
                .thenReturn(new Atendimento.FichaPaciente(maria, List.of()));

        mvc.perform(get("/atendimento/api/pacientes/77/ficha").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paciente.nome").value("Maria"))
                .andExpect(jsonPath("$.visitas").isArray());
    }

    @Test
    void declaracaoRepassaTextoEUsuario() throws Exception {
        when(atendimento.emitirDeclaracao("7", 50L, "Compareceu", "recepcao@clinica"))
                .thenReturn(Atendimento.Operacao.sucesso(4L));

        mvc.perform(post("/atendimento/api/atendimentos/50/declaracao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texto\":\"Compareceu\"}").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4));
    }

    @Test
    void declaracaoDeAtendimentoCanceladoDevolve409() throws Exception {
        when(atendimento.emitirDeclaracao(anyString(), anyLong(), any(), anyString()))
                .thenReturn(Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_DOCUMENTO));

        mvc.perform(post("/atendimento/api/atendimentos/50/declaracao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texto\":\"x\"}").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    void impressaoDaDeclaracaoDevolveHtmlSemCache() throws Exception {
        when(atendimento.impressaoDeclaracao(eq("7"), eq(4L), eq("recepcao@clinica"), any())).thenReturn("<html>Declaração</html>");

        mvc.perform(get("/atendimento/api/documentos/4/impressao").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void fichaDeOutraContaEProibida() throws Exception {
        mvc.perform(get("/atendimento/api/pacientes/77/ficha").param("conta", "8").requestAttr(AdminAuthFilter.LOGIN, RECEPCAO))
                .andExpect(status().isForbidden());
        verify(atendimento, never()).fichaDoPaciente(anyString(), anyLong(), anyString(), any());
    }
}
