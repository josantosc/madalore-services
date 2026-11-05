package br.com.mandalore.agendamentoconversa.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.CuradoriaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.TreinamentoPort;

class CuradoriaServiceTest {

    private final List<Curadoria.Decisao> registradas = new ArrayList<>();
    private final List<String> ensinadas = new ArrayList<>();
    private int diasConsultados;

    private final CuradoriaPort porta = new CuradoriaPort() {
        @Override
        public Curadoria.Indicadores indicadores(int dias, double limite) {
            diasConsultados = dias;
            return null;
        }

        @Override
        public List<Curadoria.Frase> fila(int dias, double limite) {
            diasConsultados = dias;
            return List.of();
        }

        @Override
        public void registrar(Curadoria.Decisao decisao) {
            registradas.add(decisao);
        }
    };

    private final TreinamentoPort treinamento = new TreinamentoPort() {
        @Override
        public List<String> intents() {
            return List.of("acao.agenda.agendar");
        }

        @Override
        public boolean ensinar(String intent, String frase) {
            ensinadas.add(intent + "|" + frase);
            return true;
        }
    };

    private final CuradoriaService service = new CuradoriaService(porta, treinamento, 0.6);

    @Test
    void ensinarIncluiAFraseEditadaERegistraADecisao() {
        boolean nova = service.decidir(new Curadoria.Decisao(
                "quero marcar com <cpf>", Curadoria.Acao.ENSINAR, "acao.agenda.agendar", "  quero marcar consulta ", "ana"));

        assertThat(nova).isTrue();
        assertThat(ensinadas).containsExactly("acao.agenda.agendar|quero marcar consulta");
        assertThat(registradas).singleElement().satisfies(d -> {
            assertThat(d.texto()).isEqualTo("quero marcar com <cpf>");
            assertThat(d.frase()).isEqualTo("quero marcar consulta");
            assertThat(d.usuario()).isEqualTo("ana");
        });
    }

    @Test
    void naoEnsinaFraseComDadoMascarado() {
        assertThatThrownBy(() -> service.decidir(new Curadoria.Decisao(
                "meu cpf é <cpf>", Curadoria.Acao.ENSINAR, "acao.agenda.agendar", "meu cpf é <cpf>", "ana")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("marcadores");
        assertThat(ensinadas).isEmpty();
        assertThat(registradas).isEmpty();
    }

    @Test
    void ensinarExigeIntent() {
        assertThatThrownBy(() -> service.decidir(new Curadoria.Decisao(
                "oi", Curadoria.Acao.ENSINAR, " ", "oi", "ana")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void foraDoEscopoSoRegistra() {
        service.decidir(new Curadoria.Decisao("qual a previsão do tempo", Curadoria.Acao.FORA_ESCOPO, null, null, "ana"));

        assertThat(ensinadas).isEmpty();
        assertThat(registradas).singleElement()
                .satisfies(d -> assertThat(d.acao()).isEqualTo(Curadoria.Acao.FORA_ESCOPO));
    }

    @Test
    void periodoFicaEntreUmENoventaDias() {
        service.fila(500);
        assertThat(diasConsultados).isEqualTo(90);
        service.indicadores(0);
        assertThat(diasConsultados).isEqualTo(1);
    }
}
