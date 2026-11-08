package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;
import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PacienteCadastro;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaMandalorePort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;
import br.com.mandalore.agendamentoconversa.application.domain.service.AvisoPendencia;
import br.com.mandalore.agendamentoconversa.application.domain.service.GradeHorarios;

/**
 * O bot lendo e gravando consultas na agenda do Mandalore. Exames não são oferecidos: a agenda cobre só consultas.
 * O código do horário ofertado é "g&lt;grade&gt;-&lt;yyyyMMddHHmm&gt;"; ao gravar, o horário é conferido de novo.
 */
@Component
@ConditionalOnProperty(name = {"agenda.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class MandaloreAgendaAdapter implements AgendaConsultaPort, AgendamentoEscritaPort {

    static final String USUARIO_BOT = "bot";
    private static final int LIMITE_OFERTA = 8;
    private static final DateTimeFormatter CODIGO = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final AgendaMandalorePort agenda;
    private final ContaAgenda conta;
    private final AvisoPendencia aviso;
    private final Clock relogio;
    private final int antecedenciaMinutos;

    @Autowired
    public MandaloreAgendaAdapter(
            AgendaMandalorePort agenda,
            ContaAgenda conta,
            AvisoPendencia aviso,
            @Value("${agenda.antecedencia-minutos:60}") int antecedenciaMinutos
    ) {
        this(agenda, conta, aviso, Clock.system(PostgresAgendaAdapter.ZONA), antecedenciaMinutos);
    }

    MandaloreAgendaAdapter(AgendaMandalorePort agenda, ContaAgenda conta, AvisoPendencia aviso, Clock relogio, int antecedenciaMinutos) {
        this.agenda = agenda;
        this.conta = conta;
        this.aviso = aviso;
        this.relogio = relogio;
        this.antecedenciaMinutos = antecedenciaMinutos;
    }

    @Override
    public List<PacienteCadastro> identificar(String cpfDigitos, LocalDate nascimento) {
        return agenda.paciente(conta.atual(), cpfDigitos, nascimento).stream()
                .map(p -> new PacienteCadastro(String.valueOf(p.id()), p.nome()))
                .toList();
    }

    @Override
    public List<AgendamentoAberto> agendamentosFuturos(String pacienteId) {
        Long paciente = numero(pacienteId);
        if (paciente == null) {
            return List.of();
        }
        LocalDateTime agora = LocalDateTime.now(relogio);
        return agenda.agendamentosDoPaciente(conta.atual(), paciente).stream()
                .filter(a -> a.status().ativo() && a.inicio().isAfter(agora))
                .sorted(Comparator.comparing(Agenda.Agendamento::inicio))
                .map(a -> new AgendamentoAberto(String.valueOf(a.id()), a.inicio(), a.especialidade(), a.profissional(), a.local(),
                        a.status() == Agenda.Status.CONFIRMADO ? "Confirmado" : "Marcado"))
                .toList();
    }

    @Override
    public List<ItemCatalogo> convenios(String pacienteId) {
        String clinica = conta.atual();
        Long paciente = numero(pacienteId);
        Set<Long> usados = paciente == null ? Set.of() : agenda.agendamentosDoPaciente(clinica, paciente).stream()
                .map(Agenda.Agendamento::convenioId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return agenda.itens(clinica, Agenda.Cadastro.CONVENIO).stream()
                .filter(Agenda.Item::ativo)
                .map(c -> ItemCatalogo.real(String.valueOf(c.id()), c.nome(), usados.contains(c.id())))
                .toList();
    }

    @Override
    public List<ItemCatalogo> profissionais(String pacienteId) {
        String clinica = conta.atual();
        Set<Long> comVaga = gradesDoBot(clinica).stream().map(Agenda.Grade::profissionalId).collect(Collectors.toSet());
        return agenda.itens(clinica, Agenda.Cadastro.PROFISSIONAL).stream()
                .filter(p -> p.ativo() && comVaga.contains(p.id()))
                .map(p -> ItemCatalogo.real(String.valueOf(p.id()), p.nome(), false))
                .toList();
    }

    @Override
    public List<ItemCatalogo> especialidades(String medicoId) {
        String clinica = conta.atual();
        Long medico = numero(medicoId);
        Set<Long> doMedico = gradesDoBot(clinica).stream()
                .filter(g -> medico == null || g.profissionalId() == medico)
                .map(Agenda.Grade::especialidadeId)
                .collect(Collectors.toSet());
        return agenda.itens(clinica, Agenda.Cadastro.ESPECIALIDADE).stream()
                .filter(e -> e.ativo() && doMedico.contains(e.id()))
                .map(e -> ItemCatalogo.real(String.valueOf(e.id()), e.nome(), false))
                .toList();
    }

    @Override
    public List<HorarioOferta> disponibilidade(FiltroDisponibilidade filtro) {
        String clinica = conta.atual();
        Long medico = numero(filtro.medicoId());
        Long especialidade = numero(filtro.especialidadeId());
        List<Agenda.Grade> grades = gradesDoBot(clinica).stream()
                .filter(g -> medico == null || g.profissionalId() == medico)
                .filter(g -> especialidade == null || g.especialidadeId() == especialidade)
                .toList();
        if (grades.isEmpty()) {
            return List.of();
        }
        Map<Long, String> profissionais = nomes(clinica, Agenda.Cadastro.PROFISSIONAL);
        Map<Long, String> especialidades = nomes(clinica, Agenda.Cadastro.ESPECIALIDADE);
        return vagas(clinica, grades, filtro.inicio(), filtro.fim(), filtro.periodo(), null).stream()
                .limit(LIMITE_OFERTA)
                .map(h -> new HorarioOferta(codigo(h), String.valueOf(h.gradeId()), h.inicio(),
                        profissionais.get(h.profissionalId()), especialidades.get(h.especialidadeId()), h.local()))
                .toList();
    }

    @Override
    public List<ItemCatalogo> exames() {
        return List.of();
    }

    @Override
    public List<HorarioOferta> disponibilidadeExame(String exameId, LocalDateTime inicio, LocalDateTime fim, String periodo) {
        return List.of();
    }

    @Override
    public ProcedimentoExame procedimentoExame(String exameId) {
        return null;
    }

    @Override
    public String orientacaoExame(String exameId) {
        return "";
    }

    @Override
    public ResultadoEscrita agendar(PedidoAgendar pedido) {
        if (pedido.isExame()) {
            return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
        }
        String clinica = conta.atual();
        Optional<Agenda.Horario> horario = horarioOfertavel(clinica, pedido, a -> a.inicio().equals(pedido.dataAgenda())
                && a.paciente().cpf().equals(pedido.cpf()) && a.paciente().nascimento().equals(pedido.nascimento()));
        if (horario.isEmpty()) {
            return new ResultadoEscrita(CodigoNegocio.HORARIO_INDISPONIVEL);
        }
        Agenda.Operacao operacao = agenda.marcar(clinica, marcacao(pedido, horario.get(),
                new Agenda.Paciente(null, pedido.cpf(), pedido.nascimento(), pedido.nome(), "")));
        return resultado(clinica, operacao);
    }

    @Override
    public ResultadoEscrita confirmar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        Long id = numero(nrSequencia);
        if (id == null) {
            return new ResultadoEscrita(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        }
        String clinica = conta.atual();
        return resultado(clinica, agenda.confirmar(clinica, id, alteracao(chaveIdempotencia, pacienteId)));
    }

    @Override
    public ResultadoEscrita cancelar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        Long id = numero(nrSequencia);
        if (id == null) {
            return new ResultadoEscrita(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        }
        String clinica = conta.atual();
        return resultado(clinica, agenda.cancelar(clinica, id, alteracao(chaveIdempotencia, pacienteId)));
    }

    @Override
    public ResultadoEscrita remarcar(String chaveIdempotencia, String pacienteId, String nrAtual, PedidoAgendar novoHorario) {
        Long id = numero(nrAtual);
        if (id == null) {
            return new ResultadoEscrita(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        }
        String clinica = conta.atual();
        Optional<Agenda.Horario> horario = horarioOfertavel(clinica, novoHorario, a -> a.id() == id);
        if (horario.isEmpty()) {
            return new ResultadoEscrita(CodigoNegocio.HORARIO_INDISPONIVEL);
        }
        Agenda.Operacao operacao = agenda.remarcar(clinica, id, marcacao(novoHorario, horario.get(), null),
                alteracao(chaveIdempotencia, pacienteId));
        return resultado(clinica, operacao);
    }

    /**
     * O horário pedido ainda é uma vaga do bot livre naquela grade. ignorar: o agendamento que está sendo remarcado ou a
     * repetição da mesma marcação, que o adaptador resolve pela chave de idempotência.
     */
    private Optional<Agenda.Horario> horarioOfertavel(String clinica, PedidoAgendar pedido, Predicate<Agenda.Agendamento> ignorar) {
        Long gradeId = numero(pedido.cdAgenda());
        if (gradeId == null || pedido.dataAgenda() == null) {
            return Optional.empty();
        }
        return agenda.grade(clinica, gradeId)
                .filter(g -> g.ativo() && g.ofereceBot())
                .flatMap(g -> vagas(clinica, List.of(g), pedido.dataAgenda(), pedido.dataAgenda().plusMinutes(1), "tanto_faz", ignorar)
                        .stream()
                        .filter(h -> h.inicio().equals(pedido.dataAgenda()))
                        .findFirst());
    }

    private List<Agenda.Horario> vagas(String clinica, List<Agenda.Grade> grades, LocalDateTime de, LocalDateTime ate,
                                       String periodo, Predicate<Agenda.Agendamento> ignorar) {
        if (de == null || ate == null || !de.isBefore(ate)) {
            return List.of();
        }
        LocalDateTime inicioDia = de.toLocalDate().atStartOfDay();
        LocalDateTime fimDia = ate.toLocalDate().plusDays(1).atStartOfDay();
        List<Agenda.Agendamento> ativos = agenda.agendamentos(clinica, inicioDia.minusDays(1), fimDia, true).stream()
                .filter(a -> ignorar == null || !ignorar.test(a))
                .toList();
        List<Agenda.Bloqueio> bloqueios = agenda.bloqueios(clinica, inicioDia, fimDia);
        LocalDateTime aPartirDe = LocalDateTime.now(relogio).plusMinutes(antecedenciaMinutos);
        return GradeHorarios.vagasBot(grades, ativos, bloqueios, de, ate, aPartirDe, periodo);
    }

    private Agenda.Marcacao marcacao(PedidoAgendar pedido, Agenda.Horario horario, Agenda.Paciente paciente) {
        return new Agenda.Marcacao(
                pedido.chaveIdempotencia(),
                horario.profissionalId(),
                horario.especialidadeId(),
                numero(pedido.convenioId()),
                horario.inicio(),
                horario.fim(),
                horario.local(),
                paciente,
                Agenda.Origem.BOT,
                USUARIO_BOT,
                ClienteAtual.conversationId(),
                null);
    }

    private static Agenda.Alteracao alteracao(String chave, String pacienteId) {
        return new Agenda.Alteracao(chave, numero(pacienteId), Agenda.Origem.BOT, USUARIO_BOT, false);
    }

    private ResultadoEscrita resultado(String clinica, Agenda.Operacao operacao) {
        if (operacao.codigo() == CodigoNegocio.SUCESSO) {
            aviso.criadas(clinica, operacao.pendencias());
            if (operacao.pendencias().isEmpty()) {
                aviso.atualizada(clinica, ClienteAtual.conversationId());
            }
        }
        return new ResultadoEscrita(operacao.codigo());
    }

    private List<Agenda.Grade> gradesDoBot(String clinica) {
        LocalDate hoje = LocalDate.now(relogio);
        return agenda.grades(clinica).stream().filter(g -> g.ativo() && g.ofereceBot() && g.vigenteDesde(hoje)).toList();
    }

    private Map<Long, String> nomes(String clinica, Agenda.Cadastro cadastro) {
        return agenda.itens(clinica, cadastro).stream().collect(Collectors.toMap(Agenda.Item::id, Agenda.Item::nome, (a, b) -> a));
    }

    static String codigo(Agenda.Horario horario) {
        return "g" + horario.gradeId() + "-" + CODIGO.format(horario.inicio());
    }

    static Long numero(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(texto.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
