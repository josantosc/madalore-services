package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.ContextoConversa;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.FulfillmentCommand;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.IdentidadePaciente;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PaginaLista;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoOperacao;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;

@ExtendWith(MockitoExtension.class)
class AgendaConsultaServiceTest {

    @Mock
    private AgendaConsultaPort agenda;

    @Mock
    private AgendamentoEscritaPort escrita;

    @Mock
    private FlowTelemetry telemetry;

    private IdentidadeSessao identidades;
    private AgendaConsultaService service;

    @BeforeEach
    void setUp() {
        identidades = new IdentidadeSessao();
        service = new AgendaConsultaService(agenda, escrita, identidades, telemetry);
    }

    @Test
    void disponibilidadeSemPacienteNaoConsultaOracle() {
        ResultadoOperacao resultado = service.executar(comando("acao.agenda.disponibilidade", Map.of(), new ContextoConversa()));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_NAO_IDENTIFICADO");
        assertThat(resultado.codigo()).isEqualTo(CodigoNegocio.NAO_IDENTIFICADO);
        verify(agenda, never()).disponibilidade(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void escolherForaDaOfertaMostraOsHorariosDeNovo() {
        ContextoConversa ctx = new ContextoConversa();
        ctx.setEspecialidadeNome("Ortopedia");
        ctx.setOferta(List.of(horario("10")));

        ResultadoOperacao resultado = service.executar(comando("acao.agenda.escolher", Map.of("escolha", 9), ctx));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_LISTA_HORARIOS");
        assertThat(resultado.parametros().get("lista"))
                .startsWith("Não encontrei esse número na lista.")
                .contains("1. seg 20/07, 14:30, com Ana", "Responda com o número do horário");
        assertThat(ctx.temConfirmacao()).isFalse();
    }

    @Test
    void escolherSemOfertaAvisaQueNaoAchouOHorario() {
        ResultadoOperacao resultado = service.executar(comando("acao.agenda.escolher", Map.of("escolha", 1), new ContextoConversa()));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_HORARIO_FORA_DA_OFERTA");
    }

    @Test
    void horarioOcupadoNaGravacaoOfereceOsQueSobraram() {
        ContextoConversa ctx = confirmandoHorario("10");
        when(escrita.agendar(org.mockito.ArgumentMatchers.any(PedidoAgendar.class)))
                .thenReturn(new ResultadoEscrita(CodigoNegocio.HORARIO_INDISPONIVEL));
        when(agenda.disponibilidade(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(horario("10"), horario("12")));

        ResultadoOperacao resultado = service.executar(comando("acao.agenda.agendar", Map.of("confirmacao", "sim"), ctx));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_LISTA_HORARIOS");
        assertThat(resultado.codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
        assertThat(resultado.parametros().get("lista")).startsWith("Esse horário acabou de ser ocupado.");
        assertThat(ctx.getOferta()).extracting(HorarioOferta::nrSequencia).containsExactly("12");
        assertThat(ctx.temConfirmacao()).isFalse();
    }

    @Test
    void horarioOcupadoSemOutroLivreAvisaENaoOfereceLista() {
        ContextoConversa ctx = confirmandoHorario("10");
        when(escrita.agendar(org.mockito.ArgumentMatchers.any(PedidoAgendar.class)))
                .thenReturn(new ResultadoEscrita(CodigoNegocio.HORARIO_INDISPONIVEL));
        when(agenda.disponibilidade(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(horario("10")));

        ResultadoOperacao resultado = service.executar(comando("acao.agenda.agendar", Map.of("confirmacao", "sim"), ctx));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_HORARIO_INDISPONIVEL");
        assertThat(ctx.getOferta()).isEmpty();
    }

    @Test
    void numeroInexistenteNaListaDeAgendamentosMostraAListaDeNovo() {
        ContextoConversa ctx = paciente();
        ctx.setAgendamentos(List.of(new AgendamentoAberto(
                "10", LocalDateTime.of(2026, 7, 18, 9, 0), "Ortopedia", "Ana", "Sala", "N"
        )));

        ResultadoOperacao resultado = service.executar(comando("acao.agenda.cancelar", Map.of("escolha", 5), ctx));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_SUCESSO_LISTA");
        assertThat(resultado.parametros().get("lista"))
                .startsWith("Não encontrei esse número na lista.")
                .contains("18/07/2026, Ortopedia");
    }

    @Test
    void perguntaDeCancelamentoTrazEspecialidadeDataEHora() {
        ContextoConversa ctx = paciente();
        ctx.setAgendamentos(List.of(new AgendamentoAberto(
                "10", LocalDateTime.of(2026, 7, 18, 9, 0), "Ortopedia", "Ana", "Sala", "N"
        )));

        ResultadoOperacao resultado = service.executar(comando("acao.agenda.cancelar", Map.of("escolha", 1), ctx));

        assertThat(resultado.parametros().get("texto"))
                .isEqualTo("Posso cancelar a consulta de Ortopedia do dia 18/07/2026, às 09:00? Responda sim ou não.");
    }

    @Test
    void identificarDepoisDeEscolherConsultaVaiDiretoAosConvenios() {
        ContextoConversa ctx = new ContextoConversa();
        ctx.setObjetivo("consulta");
        when(agenda.identificar("12345678901", LocalDate.of(1988, 10, 19)))
                .thenReturn(List.of(new br.com.mandalore.agendamentoconversa.application.domain.model.PacienteCadastro("1", "Maria da Silva")));
        when(agenda.agendamentosFuturos("1")).thenReturn(List.of());
        when(agenda.convenios("1")).thenReturn(List.of(ItemCatalogo.real("1", "Particular", false)));

        ResultadoOperacao resultado = service.executar(comando(
                "acao.paciente.identificar",
                Map.of("cpf", "123.456.789-01", "data_nascimento", "1988-10-19"),
                ctx
        ));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_SUCESSO_LISTA");
        assertThat(resultado.parametros().get("lista"))
                .startsWith("Encontrei seu cadastro, Maria.")
                .contains("Qual convênio", "1. Particular");
        assertThat(ctx.getLista().tipo()).isEqualTo("convenio");
        assertThat(ctx.isLimparObjetivo()).isTrue();
    }

    @Test
    void escolherGravaConfirmacaoSemChamarEscrita() {
        ContextoConversa ctx = paciente();
        ctx.setEspecialidadeNome("Ortopedia");
        ctx.setConvenioNome("Amil");
        ctx.setOferta(List.of(horario("10"), horario("11")));

        ResultadoOperacao resultado = service.executar(comando("acao.agenda.escolher", Map.of("escolha", 2), ctx));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_CONFIRMACAO_AUSENTE");
        assertThat(ctx.getConfirmacaoNr()).isEqualTo("11");
        assertThat(resultado.parametros().get("texto"))
                .contains(
                        "Maria, confirme os dados",
                        "Consulta com: Ana",
                        "Especialidade: Ortopedia",
                        "Convênio: Amil",
                        "Posso confirmar?"
                );
        verify(escrita, never()).agendar(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void agendarSemConfirmacaoNaoChamaAgendamentoDs() {
        ResultadoOperacao resultado = service.executar(comando("acao.agenda.agendar", Map.of("confirmacao", "sim"), paciente()));

        assertThat(resultado.codigo()).isEqualTo(CodigoNegocio.CONFIRMACAO_AUSENTE);
        verify(escrita, never()).agendar(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void agendarComSimChamaAgendamentoDs() {
        ContextoConversa ctx = paciente();
        HorarioOferta horario = horario("10");
        ctx.setOferta(List.of(horario));
        ctx.setConfirmacaoNr("10");
        ctx.setConfirmacaoTipo("agendar");
        ctx.setConfirmacaoCdAgenda("99");
        ctx.setConfirmacaoDataHora(horario.dataHora());
        ctx.setConfirmacaoEspecialidade("Ortopedia");
        ctx.setConfirmacaoMedico("Ana");
        ctx.setConvenioNome("Particular");
        identidades.guardar("1", new IdentidadePaciente("12345678901", LocalDate.of(1988, 10, 19), "Maria Silva"));
        when(escrita.agendar(org.mockito.ArgumentMatchers.any(PedidoAgendar.class)))
                .thenReturn(new ResultadoEscrita(CodigoNegocio.SUCESSO));

        ResultadoOperacao resultado = service.executar(comando(
                "acao.agenda.agendar",
                Map.of("confirmacao", "sim"),
                ctx
        ));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_SUCESSO_ESCRITA");
        assertThat(resultado.parametros().get("texto"))
                .contains(
                        "Agendamento confirmado, Maria!",
                        "Consulta com: Ana",
                        "Especialidade: Ortopedia",
                        "Convênio: Particular",
                        "Posso ajudar em mais alguma coisa?"
                );
        assertThat(ctx.isLimparConfirmacao()).isTrue();
    }

    @Test
    void simNaConfirmacaoDeCancelamentoCancela() {
        ContextoConversa ctx = paciente();
        ctx.setAgendamentos(List.of(new AgendamentoAberto(
                "10",
                LocalDateTime.of(2026, 7, 18, 9, 0),
                "Ortopedia",
                "Ana",
                "Sala",
                "N"
        )));
        ctx.setConfirmacaoNr("10");
        ctx.setConfirmacaoTipo("cancelar");
        ctx.setConfirmacaoDataHora(LocalDateTime.of(2026, 7, 18, 9, 0));
        when(escrita.cancelar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("1"), org.mockito.ArgumentMatchers.eq("10")))
                .thenReturn(new ResultadoEscrita(CodigoNegocio.SUCESSO));

        ResultadoOperacao resultado = service.executar(comando("acao.agenda.agendar", Map.of("confirmacao", "sim"), ctx));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_SUCESSO_ESCRITA");
        assertThat(resultado.parametros().get("acao")).isEqualTo("cancelar");
        assertThat(resultado.parametros().get("texto")).contains("Cancelei");
    }

    @Test
    void pacienteNaoEncontradoOfereceFluxoSemCadastro() {
        ContextoConversa ctx = new ContextoConversa();
        when(agenda.identificar("12345678901", LocalDate.of(1988, 10, 19)))
                .thenReturn(List.of());

        ResultadoOperacao resultado = service.executar(comando(
                "acao.paciente.identificar",
                Map.of("cpf", "123.456.789-01", "data_nascimento", "1988-10-19"),
                ctx
        ));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_PACIENTE_NAO_ENCONTRADO");
        assertThat(resultado.parametros())
                .containsEntry("cpf", "12345678901")
                .containsEntry("nascimento", "1988-10-19");
        assertThat(ctx.isSemCadastro()).isTrue();
        assertThat(ctx.getCpf()).isEqualTo("12345678901");
        assertThat(ctx.getNascimento()).isEqualTo(LocalDate.of(1988, 10, 19));
        assertThat(ctx.temPaciente()).isFalse();
    }

    @Test
    void confirmaIdentidadeSemCadastroComNomeCompleto() {
        ContextoConversa ctx = semCadastro();
        when(agenda.convenios(null)).thenReturn(List.of(
                ItemCatalogo.real("1", "Particular", false),
                ItemCatalogo.real("2", "Unimed", false)
        ));

        ResultadoOperacao resultado = service.executar(comando(
                "acao.paciente.sem_cadastro",
                Map.of("nome", "Maria da Silva"),
                ctx
        ));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_LISTA_CONVENIO");
        assertThat(resultado.parametros().get("lista")).contains("Particular", "Ver outros");
        assertThat(ctx.getNome()).isEqualTo("Maria da Silva");
        assertThat(ctx.temIdentidadeAgendamento()).isTrue();
    }

    @Test
    void escolherConvenioPerguntaSeBuscaPorClinicaOuMedico() {
        ContextoConversa ctx = semCadastro();
        ctx.setNome("Maria da Silva");
        ItemCatalogo particular = ItemCatalogo.real("1", "Particular", false);
        ctx.setLista(new PaginaLista("convenio", 0, List.of(particular), List.of(particular)));

        ResultadoOperacao resultado = service.executar(comando(
                "acao.consulta.escolher_lista",
                Map.of("escolha", 1),
                ctx
        ));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_TIPO_BUSCA");
        assertThat(ctx.getConvenioId()).isEqualTo("1");
    }

    @Test
    void escolherEspecialidadePerguntaPeriodoAntesDosHorarios() {
        ContextoConversa ctx = semCadastro();
        ctx.setNome("Maria da Silva");
        ItemCatalogo cardiologia = ItemCatalogo.real("10", "Cardiologia", false);
        ctx.setLista(new PaginaLista("especialidade", 0, List.of(cardiologia), List.of(cardiologia)));

        ResultadoOperacao resultado = service.executar(comando(
                "acao.consulta.escolher_lista",
                Map.of("escolha", 1),
                ctx
        ));

        assertThat(resultado.evento()).isEqualTo("RESULTADO_PERIODO");
        assertThat(ctx.getEspecialidadeId()).isEqualTo("10");
        verify(agenda, never()).disponibilidade(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void escolherPeriodoConsultaProximosHorarios() {
        ContextoConversa ctx = semCadastro();
        ctx.setNome("Maria da Silva");
        ctx.setEspecialidadeId("10");
        ctx.setEspecialidadeNome("Cardiologia");
        when(agenda.disponibilidade(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(horario("10")));

        ResultadoOperacao resultado = service.executar(comando(
                "acao.agenda.disponibilidade",
                Map.of("periodo", "manha"),
                ctx
        ));

        ArgumentCaptor<FiltroDisponibilidade> captor = ArgumentCaptor.forClass(FiltroDisponibilidade.class);
        verify(agenda).disponibilidade(captor.capture());
        assertThat(resultado.evento()).isEqualTo("RESULTADO_LISTA_HORARIOS");
        assertThat(captor.getValue().especialidadeId()).isEqualTo("10");
        assertThat(captor.getValue().medicoId()).isNull();
        assertThat(captor.getValue().periodo()).isEqualTo("manha");
        assertThat(resultado.parametros().get("lista")).doesNotContain("Consultório");
        assertThat(ctx.getOferta()).hasSize(1);
    }

    @Test
    void agendaSemCadastroSemCriarIdentidadePessoaFisica() {
        ContextoConversa ctx = semCadastro();
        ctx.setNome("Maria da Silva");
        HorarioOferta horario = horario("10");
        ctx.setOferta(List.of(horario));
        ctx.setConfirmacaoNr("10");
        ctx.setConfirmacaoTipo("agendar");
        ctx.setConfirmacaoCdAgenda("99");
        ctx.setConfirmacaoDataHora(horario.dataHora());
        ctx.setConfirmacaoEspecialidade("Ortopedia");
        ctx.setConfirmacaoMedico("Ana");
        when(escrita.agendar(org.mockito.ArgumentMatchers.any(PedidoAgendar.class)))
                .thenReturn(new ResultadoEscrita(CodigoNegocio.SUCESSO));

        ResultadoOperacao resultado = service.executar(comando(
                "acao.agenda.agendar",
                Map.of("confirmacao", "sim"),
                ctx
        ));

        ArgumentCaptor<PedidoAgendar> captor = ArgumentCaptor.forClass(PedidoAgendar.class);
        verify(escrita).agendar(captor.capture());
        assertThat(resultado.evento()).isEqualTo("RESULTADO_SUCESSO_ESCRITA");
        assertThat(captor.getValue().semCadastro()).isTrue();
        assertThat(captor.getValue().pacienteId()).isNull();
        assertThat(captor.getValue().nome()).isEqualTo("Maria da Silva");
    }

    private ContextoConversa paciente() {
        ContextoConversa ctx = new ContextoConversa();
        ctx.setPacienteId("1");
        ctx.setNome("Maria");
        return ctx;
    }

    private ContextoConversa confirmandoHorario(String nr) {
        ContextoConversa ctx = paciente();
        HorarioOferta horario = horario(nr);
        ctx.setEspecialidadeId("5");
        ctx.setEspecialidadeNome("Ortopedia");
        ctx.setPeriodo("tarde");
        ctx.setDia(LocalDate.of(2026, 7, 20));
        ctx.setOferta(List.of(horario));
        ctx.setConfirmacaoNr(nr);
        ctx.setConfirmacaoTipo("agendar");
        ctx.setConfirmacaoCdAgenda("99");
        ctx.setConfirmacaoDataHora(horario.dataHora());
        identidades.guardar("1", new IdentidadePaciente("12345678901", LocalDate.of(1988, 10, 19), "Maria Silva"));
        return ctx;
    }

    private ContextoConversa semCadastro() {
        ContextoConversa ctx = new ContextoConversa();
        ctx.setSemCadastro(true);
        ctx.setCpf("12345678901");
        ctx.setNascimento(LocalDate.of(1988, 10, 19));
        return ctx;
    }

    private HorarioOferta horario(String nr) {
        return new HorarioOferta(nr, "99", LocalDateTime.of(2026, 7, 20, 14, 30), "Ana", "Ortopedia", "Consultório");
    }

    private FulfillmentCommand comando(String intent, Map<String, Object> parametros, ContextoConversa ctx) {
        return new FulfillmentCommand("projects/p/agent/sessions/cw-1-9", intent, "", parametros, ctx);
    }
}
