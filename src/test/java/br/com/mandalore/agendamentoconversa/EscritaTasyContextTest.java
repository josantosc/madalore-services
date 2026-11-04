package br.com.mandalore.agendamentoconversa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import br.com.mandalore.agendamentoconversa.adapter.out.tasy.TasyAgendamentoAdapter;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;

@SpringBootTest(properties = {
        "agendamento.escrita=tasy",
        "mandalore.clientes.2.tasy.url=jdbc:oracle:thin:@//tasy-hospital-2:1521/TASY",
        "mandalore.clientes.2.tasy.username=mandalore",
        "mandalore.clientes.2.tasy.password=x"
})
class EscritaTasyContextTest {

    @Autowired
    private AgendamentoEscritaPort escrita;

    @Test
    void gravacaoVaiDiretoParaOTasyDoCliente() {
        assertThat(escrita).isInstanceOf(TasyAgendamentoAdapter.class);
    }
}
