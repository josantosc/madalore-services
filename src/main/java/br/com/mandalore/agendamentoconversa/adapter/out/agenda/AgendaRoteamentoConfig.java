package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PacienteCadastro;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;

/**
 * Com a agenda ligada, o bot escolhe a agenda por conversa: a do Mandalore para as contas com agenda=mandalore e o
 * adaptador de sempre (Tasy, agendamento-ds) para as demais.
 */
@Configuration
@ConditionalOnProperty(name = {"agenda.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class AgendaRoteamentoConfig {

    @Bean
    @Primary
    AgendaConsultaPort agendaConsultaRoteada(List<AgendaConsultaPort> todos, MandaloreAgendaAdapter mandalore, OrigemAgenda origem) {
        return new ConsultaRoteada(padrao(todos, mandalore), mandalore, origem);
    }

    @Bean
    @Primary
    AgendamentoEscritaPort agendamentoEscritaRoteado(List<AgendamentoEscritaPort> todos, MandaloreAgendaAdapter mandalore, OrigemAgenda origem) {
        return new EscritaRoteada(padrao(todos, mandalore), mandalore, origem);
    }

    static <T> T padrao(List<T> todos, T mandalore) {
        return todos.stream()
                .filter(porta -> porta != mandalore && !(porta instanceof ConsultaRoteada) && !(porta instanceof EscritaRoteada))
                .findFirst()
                .orElse(mandalore);
    }

    record ConsultaRoteada(AgendaConsultaPort padrao, AgendaConsultaPort mandalore, OrigemAgenda origem) implements AgendaConsultaPort {

        private AgendaConsultaPort alvo() {
            return origem.mandalore() ? mandalore : padrao;
        }

        @Override
        public List<PacienteCadastro> identificar(String cpfDigitos, LocalDate nascimento) {
            return alvo().identificar(cpfDigitos, nascimento);
        }

        @Override
        public List<AgendamentoAberto> agendamentosFuturos(String pacienteId) {
            return alvo().agendamentosFuturos(pacienteId);
        }

        @Override
        public List<ItemCatalogo> convenios(String pacienteId) {
            return alvo().convenios(pacienteId);
        }

        @Override
        public List<ItemCatalogo> profissionais(String pacienteId) {
            return alvo().profissionais(pacienteId);
        }

        @Override
        public List<ItemCatalogo> especialidades(String medicoId) {
            return alvo().especialidades(medicoId);
        }

        @Override
        public List<HorarioOferta> disponibilidade(FiltroDisponibilidade filtro) {
            return alvo().disponibilidade(filtro);
        }

        @Override
        public List<ItemCatalogo> exames() {
            return alvo().exames();
        }

        @Override
        public List<HorarioOferta> disponibilidadeExame(String exameId, LocalDateTime inicio, LocalDateTime fim, String periodo) {
            return alvo().disponibilidadeExame(exameId, inicio, fim, periodo);
        }

        @Override
        public ProcedimentoExame procedimentoExame(String exameId) {
            return alvo().procedimentoExame(exameId);
        }

        @Override
        public String orientacaoExame(String exameId) {
            return alvo().orientacaoExame(exameId);
        }
    }

    record EscritaRoteada(AgendamentoEscritaPort padrao, AgendamentoEscritaPort mandalore, OrigemAgenda origem) implements AgendamentoEscritaPort {

        private AgendamentoEscritaPort alvo() {
            return origem.mandalore() ? mandalore : padrao;
        }

        @Override
        public ResultadoEscrita agendar(PedidoAgendar pedido) {
            return alvo().agendar(pedido);
        }

        @Override
        public ResultadoEscrita confirmar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
            return alvo().confirmar(chaveIdempotencia, pacienteId, nrSequencia);
        }

        @Override
        public ResultadoEscrita cancelar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
            return alvo().cancelar(chaveIdempotencia, pacienteId, nrSequencia);
        }

        @Override
        public ResultadoEscrita remarcar(String chaveIdempotencia, String pacienteId, String nrAtual, PedidoAgendar novoHorario) {
            return alvo().remarcar(chaveIdempotencia, pacienteId, nrAtual, novoHorario);
        }
    }
}
