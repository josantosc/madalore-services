package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;

class MandaloreAgendaAdapterTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    /** Terça, 10/11/2026, 07:00. */
    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 11, 10, 7, 0);
    private static final LocalDate NASCIMENTO = LocalDate.of(1988, 10, 19);

    private final AgendaMemoria memoria = AgendaMemoria.exemplo();
    private final MandaloreAgendaAdapter adaptador = memoria.adaptador(Clock.fixed(AGORA.atZone(SP).toInstant(), SP));

    @Test
    void ofereceNoMaximoOitoHorariosComCodigoDaGrade() {
        List<HorarioOferta> oferta = adaptador.disponibilidade(filtro("tanto_faz"));

        assertThat(oferta).hasSize(8);
        assertThat(oferta.get(0).dataHora()).isEqualTo(AGORA.withHour(8));
        assertThat(oferta.get(0).nrSequencia()).matches("g\\d+-202611100800");
        assertThat(oferta.get(0).medico()).isEqualTo("Dra. Ana Mandalore");
        assertThat(oferta.get(0).especialidade()).isEqualTo("Cardiologia");
        assertThat(adaptador.disponibilidade(filtro("tarde"))).isEmpty();
    }

    @Test
    void agendarRevalidaOHorarioEGravaComPendenciaDeLancar() {
        HorarioOferta primeiro = adaptador.disponibilidade(filtro("manha")).get(0);

        assertThat(adaptador.agendar(pedido("k1", primeiro)).codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adaptador.agendar(pedido("k2", primeiro)).codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
        assertThat(adaptador.agendar(pedido("k1", primeiro)).codigo()).as("mesma chave não grava de novo").isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(memoria.todos()).hasSize(1);
        assertThat(memoria.todasPendencias()).hasSize(1);
        assertThat(adaptador.disponibilidade(filtro("manha"))).extracting(HorarioOferta::nrSequencia).doesNotContain(primeiro.nrSequencia());
    }

    @Test
    void horarioForaDaGradeOuNoPassadoNaoGrava() {
        HorarioOferta inventado = new HorarioOferta("x", "1", AGORA.withHour(8).withMinute(10), null, null, null);
        HorarioOferta passado = new HorarioOferta("x", String.valueOf(memoria.grades("1").get(0).id()), AGORA.minusDays(1).withHour(8), null, null, null);

        assertThat(adaptador.agendar(pedido("k3", inventado)).codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
        assertThat(adaptador.agendar(pedido("k4", passado)).codigo()).isEqualTo(CodigoNegocio.HORARIO_INDISPONIVEL);
    }

    @Test
    void pacienteMarcadoPeloBotEhIdentificadoECancelaPeloBot() {
        adaptador.agendar(pedido("k5", adaptador.disponibilidade(filtro("manha")).get(0)));

        String pacienteId = adaptador.identificar("52998224725", NASCIMENTO).get(0).id();
        assertThat(adaptador.agendamentosFuturos(pacienteId)).hasSize(1);
        String agendamento = adaptador.agendamentosFuturos(pacienteId).get(0).nrSequencia();

        assertThat(adaptador.cancelar("k6", "999", agendamento).codigo()).as("outro paciente").isEqualTo(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        assertThat(adaptador.cancelar("k7", pacienteId, agendamento).codigo()).isEqualTo(CodigoNegocio.SUCESSO);
        assertThat(adaptador.agendamentosFuturos(pacienteId)).isEmpty();
        assertThat(memoria.todasPendencias()).allMatch(p -> p.situacao().name().equals("DESCARTADA"));
    }

    @Test
    void catalogoSaiDosCadastrosEExameNaoEhOferecido() {
        assertThat(adaptador.convenios(null)).extracting(ItemCatalogo::nome).containsExactly("Particular", "Unimed");
        assertThat(adaptador.profissionais(null)).extracting(ItemCatalogo::nome).containsExactly("Dra. Ana Mandalore");
        assertThat(adaptador.especialidades(null)).extracting(ItemCatalogo::nome).containsExactly("Cardiologia");
        assertThat(adaptador.exames()).isEmpty();
        assertThat(adaptador.agendar(new PedidoAgendar("k8", null, "1", AGORA.withHour(8), "52998224725", NASCIMENTO, "Maria", true,
                new ProcedimentoExame("1", "2", "3", "4"), null)).codigo()).isEqualTo(CodigoNegocio.INDISPONIVEL);
    }

    private static FiltroDisponibilidade filtro(String periodo) {
        return new FiltroDisponibilidade(null, null, AGORA.toLocalDate().atStartOfDay(), AGORA.toLocalDate().plusDays(1).atStartOfDay(), periodo);
    }

    private static PedidoAgendar pedido(String chave, HorarioOferta horario) {
        return new PedidoAgendar(chave, null, horario.cdAgenda(), horario.dataHora(), "52998224725", NASCIMENTO, "Maria Teste", true, null, null);
    }
}
