package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mockito.Mockito;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaMandalorePort;
import br.com.mandalore.agendamentoconversa.application.domain.service.AvisoPendencia;

/**
 * Agenda do Mandalore em memória para os testes do bot. Marcar cria pendência de lançar e cancelar antes de lançar
 * a descarta; as demais regras de pendência são cobertas pelo PostgresAgendaAdapterIT.
 */
public class AgendaMemoria implements AgendaMandalorePort {

    private final Map<Agenda.Cadastro, List<Agenda.Item>> itens = new HashMap<>();
    private final List<Agenda.Grade> grades = new ArrayList<>();
    private final List<Agenda.Paciente> pacientes = new ArrayList<>();
    private final List<Agenda.Agendamento> agendamentos = new ArrayList<>();
    private final List<Agenda.Pendencia> pendencias = new ArrayList<>();
    private final Map<String, CodigoNegocio> operacoes = new HashMap<>();
    private long sequencia = 1;

    /** Clínica de exemplo: Dra. Ana (Cardiologia), todo dia das 8h às 12h, consultas de 30 minutos. */
    public static AgendaMemoria exemplo() {
        AgendaMemoria agenda = new AgendaMemoria();
        Agenda.Item ana = agenda.item(Agenda.Cadastro.PROFISSIONAL, "Dra. Ana Mandalore");
        Agenda.Item cardio = agenda.item(Agenda.Cadastro.ESPECIALIDADE, "Cardiologia");
        agenda.item(Agenda.Cadastro.CONVENIO, "Particular");
        agenda.item(Agenda.Cadastro.CONVENIO, "Unimed");
        for (int dia = 1; dia <= 7; dia++) {
            agenda.grades.add(new Agenda.Grade(agenda.sequencia++, ana.id(), cardio.id(), dia, null, null, null, LocalTime.of(8, 0), LocalTime.of(12, 0),
                    30, true, "Consultório 1", true));
        }
        return agenda;
    }

    /** Adaptador do bot sobre esta agenda, conta "1", sem antecedência mínima. */
    public MandaloreAgendaAdapter adaptador(Clock relogio) {
        return new MandaloreAgendaAdapter(this, new ContaAgenda("1"), Mockito.mock(AvisoPendencia.class), relogio, 0);
    }

    public Agenda.Item item(Agenda.Cadastro cadastro, String nome) {
        Agenda.Item item = new Agenda.Item(sequencia++, nome, true);
        itens.computeIfAbsent(cadastro, c -> new ArrayList<>()).add(item);
        return item;
    }

    public List<Agenda.Agendamento> todos() {
        return List.copyOf(agendamentos);
    }

    public List<Agenda.Pendencia> todasPendencias() {
        return List.copyOf(pendencias);
    }

    @Override
    public List<String> contas() {
        return List.of("1");
    }

    @Override
    public List<Agenda.Item> itens(String conta, Agenda.Cadastro cadastro) {
        return itens.getOrDefault(cadastro, List.of());
    }

    @Override
    public Agenda.Item salvarItem(String conta, Agenda.Cadastro cadastro, Agenda.Item item) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Agenda.Exclusao excluirItem(String conta, Agenda.Cadastro cadastro, long id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<Long, List<Long>> especialidadesDosProfissionais(String conta) {
        Map<Long, List<Long>> vinculos = new HashMap<>();
        grades.forEach(g -> {
            List<Long> doProfissional = vinculos.computeIfAbsent(g.profissionalId(), p -> new ArrayList<>());
            if (!doProfissional.contains(g.especialidadeId())) {
                doProfissional.add(g.especialidadeId());
            }
        });
        return vinculos;
    }

    @Override
    public void vincularEspecialidades(String conta, long profissionalId, List<Long> especialidades) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Agenda.Grade> grades(String conta) {
        return List.copyOf(grades);
    }

    @Override
    public Optional<Agenda.Grade> grade(String conta, long id) {
        return grades.stream().filter(g -> g.id() == id).findFirst();
    }

    @Override
    public Agenda.Grade salvarGrade(String conta, Agenda.Grade grade) {
        Agenda.Grade salva = new Agenda.Grade(sequencia++, grade.profissionalId(), grade.especialidadeId(), grade.diaSemana(),
                grade.data(), grade.vigenciaInicio(), grade.vigenciaFim(), grade.inicio(), grade.fim(), grade.duracaoMin(),
                grade.ofereceBot(), grade.local(), grade.ativo());
        grades.add(salva);
        return salva;
    }

    @Override
    public boolean removerGrade(String conta, long id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Agenda.Bloqueio> bloqueios(String conta, LocalDateTime de, LocalDateTime ate) {
        return List.of();
    }

    @Override
    public Agenda.Bloqueio bloquear(String conta, Agenda.Bloqueio bloqueio) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean desbloquear(String conta, long id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Agenda.Paciente> paciente(String conta, String cpf, LocalDate nascimento) {
        return pacientes.stream().filter(p -> p.cpf().equals(cpf) && p.nascimento().equals(nascimento)).findFirst();
    }

    @Override
    public List<Agenda.Paciente> buscarPacientes(String conta, String termo) {
        return List.of();
    }

    @Override
    public List<Agenda.Agendamento> agendamentos(String conta, LocalDateTime de, LocalDateTime ate, boolean ativos) {
        return agendamentos.stream()
                .filter(a -> !a.inicio().isBefore(de) && a.inicio().isBefore(ate) && (!ativos || a.status().ativo()))
                .toList();
    }

    @Override
    public List<Agenda.Agendamento> agendamentosDoPaciente(String conta, long pacienteId) {
        return agendamentos.stream().filter(a -> a.paciente().id() == pacienteId)
                .sorted(Comparator.comparing(Agenda.Agendamento::inicio).reversed()).toList();
    }

    @Override
    public Optional<Agenda.Agendamento> agendamento(String conta, long id) {
        return agendamentos.stream().filter(a -> a.id() == id).findFirst();
    }

    @Override
    public List<Agenda.Evento> historico(String conta, long agendamentoId) {
        return List.of();
    }

    @Override
    public Agenda.Operacao marcar(String conta, Agenda.Marcacao marcacao) {
        if (marcacao.chave() != null && operacoes.containsKey(marcacao.chave())) {
            return Agenda.Operacao.falha(operacoes.get(marcacao.chave()));
        }
        boolean ocupado = agendamentos.stream().anyMatch(a -> a.status().ativo() && a.profissionalId() == marcacao.profissionalId()
                && a.inicio().isBefore(marcacao.fim()) && marcacao.inicio().isBefore(a.fim()));
        if (ocupado) {
            return registrar(marcacao.chave(), Agenda.Operacao.falha(CodigoNegocio.HORARIO_INDISPONIVEL));
        }
        Agenda.Paciente paciente = paciente(conta, marcacao.paciente().cpf(), marcacao.paciente().nascimento()).orElseGet(() -> {
            Agenda.Paciente novo = new Agenda.Paciente(sequencia++, marcacao.paciente().cpf(), marcacao.paciente().nascimento(),
                    marcacao.paciente().nome(), marcacao.paciente().telefone());
            pacientes.add(novo);
            return novo;
        });
        long id = sequencia++;
        agendamentos.add(new Agenda.Agendamento(id, marcacao.profissionalId(), nome(Agenda.Cadastro.PROFISSIONAL, marcacao.profissionalId()),
                marcacao.especialidadeId(), nome(Agenda.Cadastro.ESPECIALIDADE, marcacao.especialidadeId()), marcacao.convenioId(),
                marcacao.convenioId() == null ? null : nome(Agenda.Cadastro.CONVENIO, marcacao.convenioId()), paciente,
                marcacao.inicio(), marcacao.fim(), marcacao.local(), Agenda.Status.MARCADO, marcacao.origem(), marcacao.codigoExterno(),
                marcacao.conversaId(), null, List.of(Agenda.TipoPendencia.LANCAR), null));
        Agenda.Pendencia pendencia = new Agenda.Pendencia(sequencia++, id, Agenda.TipoPendencia.LANCAR, Agenda.SituacaoPendencia.ABERTA,
                "Lançar", marcacao.inicio(), null, null, null, null, "", marcacao.conversaId(), marcacao.inicio(),
                paciente.nome());
        pendencias.add(pendencia);
        return registrar(marcacao.chave(), new Agenda.Operacao(CodigoNegocio.SUCESSO, id, List.of(pendencia)));
    }

    @Override
    public Agenda.Operacao confirmar(String conta, long agendamentoId, Agenda.Alteracao alteracao) {
        return alterarStatus(agendamentoId, alteracao, Agenda.Status.CONFIRMADO);
    }

    @Override
    public Agenda.Operacao cancelar(String conta, long agendamentoId, Agenda.Alteracao alteracao) {
        Agenda.Operacao operacao = alterarStatus(agendamentoId, alteracao, Agenda.Status.CANCELADO);
        if (operacao.codigo() == CodigoNegocio.SUCESSO) {
            pendencias.replaceAll(p -> p.agendamentoId() == agendamentoId && p.situacao() == Agenda.SituacaoPendencia.ABERTA
                    ? new Agenda.Pendencia(p.id(), p.agendamentoId(), p.tipo(), Agenda.SituacaoPendencia.DESCARTADA, p.resumo(), p.prazo(),
                    p.criadaEm(), null, alteracao.usuario(), null, "", p.conversaId(), p.inicio(), p.paciente())
                    : p);
        }
        return operacao;
    }

    @Override
    public Agenda.Operacao remarcar(String conta, long agendamentoId, Agenda.Marcacao nova, Agenda.Alteracao alteracao) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Agenda.Pendencia> pendencias(String conta, Agenda.SituacaoPendencia situacao) {
        return pendencias.stream().filter(p -> situacao == null || p.situacao() == situacao).toList();
    }

    @Override
    public Optional<Agenda.Pendencia> pendencia(String conta, long id) {
        return pendencias.stream().filter(p -> p.id() == id).findFirst();
    }

    @Override
    public boolean concluirPendencia(String conta, long id, String usuario, String codigoExterno, String observacao) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean reabrirPendencia(String conta, long id, String usuario) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long pendenciasAbertasDaConversa(String conta, String conversaId) {
        return pendencias.stream().filter(p -> p.situacao() == Agenda.SituacaoPendencia.ABERTA && conversaId.equals(p.conversaId())).count();
    }

    @Override
    public Agenda.ResumoPendencias resumo(String conta, LocalDateTime agora) {
        long abertas = pendencias.stream().filter(p -> p.situacao() == Agenda.SituacaoPendencia.ABERTA).count();
        return new Agenda.ResumoPendencias(abertas, 0, null, 0, 0);
    }

    @Override
    public void conferir(String conta, long agendamentoId, Agenda.SituacaoConferencia situacao, String observacao, String usuario) {
        throw new UnsupportedOperationException();
    }

    private Agenda.Operacao alterarStatus(long agendamentoId, Agenda.Alteracao alteracao, Agenda.Status status) {
        if (alteracao.chave() != null && operacoes.containsKey(alteracao.chave())) {
            return Agenda.Operacao.falha(operacoes.get(alteracao.chave()));
        }
        for (int i = 0; i < agendamentos.size(); i++) {
            Agenda.Agendamento a = agendamentos.get(i);
            if (a.id() == agendamentoId && a.status().ativo()
                    && (alteracao.pacienteId() == null || alteracao.pacienteId().equals(a.paciente().id()))) {
                agendamentos.set(i, new Agenda.Agendamento(a.id(), a.profissionalId(), a.profissional(), a.especialidadeId(), a.especialidade(),
                        a.convenioId(), a.convenio(), a.paciente(), a.inicio(), a.fim(), a.local(), status, a.origem(), a.codigoExterno(),
                        a.conversaId(), a.remarcadoDe(), a.pendenciasAbertas(), a.conferencia()));
                return registrar(alteracao.chave(), new Agenda.Operacao(CodigoNegocio.SUCESSO, agendamentoId, List.of()));
            }
        }
        return registrar(alteracao.chave(), Agenda.Operacao.falha(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO));
    }

    private Agenda.Operacao registrar(String chave, Agenda.Operacao operacao) {
        if (chave != null) {
            operacoes.put(chave, operacao.codigo());
        }
        return operacao;
    }

    private String nome(Agenda.Cadastro cadastro, long id) {
        return itens(null, cadastro).stream().filter(i -> i.id() == id).map(Agenda.Item::nome).findFirst().orElse(null);
    }
}
