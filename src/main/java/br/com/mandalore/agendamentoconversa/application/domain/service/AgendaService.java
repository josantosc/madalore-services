package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AgendaUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaMandalorePort;

@Service
@ConditionalOnProperty(name = {"agenda.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class AgendaService implements AgendaUseCase {

    static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    static final String OCUPADO_NO_SISTEMA = "Ocupado no sistema da clínica";

    private final AgendaMandalorePort agenda;
    private final AvisoPendencia aviso;
    private final Clock relogio;
    private final int antecedenciaMinutos;

    @Autowired
    public AgendaService(
            AgendaMandalorePort agenda,
            AvisoPendencia aviso,
            @Value("${agenda.antecedencia-minutos:60}") int antecedenciaMinutos
    ) {
        this(agenda, aviso, Clock.system(ZONA), antecedenciaMinutos);
    }

    AgendaService(AgendaMandalorePort agenda, AvisoPendencia aviso, Clock relogio, int antecedenciaMinutos) {
        this.agenda = agenda;
        this.aviso = aviso;
        this.relogio = relogio;
        this.antecedenciaMinutos = antecedenciaMinutos;
    }

    @Override
    public List<String> contas() {
        return agenda.contas();
    }

    @Override
    public Dia dia(String conta, LocalDate dia) {
        LocalDate segunda = VisaoAgenda.inicioSemana(dia);
        LocalDateTime de = segunda.atStartOfDay();
        LocalDateTime ate = segunda.plusDays(7).atStartOfDay();
        List<Agenda.Grade> grades = agenda.grades(conta);
        List<Agenda.Agendamento> ativos = agenda.agendamentos(conta, de.minusDays(1), ate, true);
        List<Agenda.Bloqueio> bloqueios = agenda.bloqueios(conta, de, ate);
        List<Agenda.LinhaDia> linhas = VisaoAgenda.dia(dia, grades, ativos, bloqueios,
                nomes(conta, Agenda.Cadastro.PROFISSIONAL), nomes(conta, Agenda.Cadastro.ESPECIALIDADE));
        LocalDateTime aPartirDe = LocalDateTime.now(relogio).plusMinutes(antecedenciaMinutos);
        return new Dia(dia, linhas, VisaoAgenda.semana(dia, grades, ativos, bloqueios, aPartirDe));
    }

    @Override
    public Detalhe detalhe(String conta, long agendamentoId) {
        Agenda.Agendamento agendamento = agenda.agendamento(conta, agendamentoId)
                .orElseThrow(() -> new IllegalArgumentException("Agendamento não encontrado"));
        List<Agenda.Pendencia> pendencias = agenda.pendencias(conta, null).stream()
                .filter(p -> p.agendamentoId() == agendamentoId)
                .toList();
        return new Detalhe(agendamento, agenda.historico(conta, agendamentoId), pendencias);
    }

    @Override
    public Agenda.Operacao marcar(String conta, NovaConsulta consulta, String usuario) {
        String cpf = cpf(consulta.cpf());
        if (consulta.nascimento() == null) {
            throw new IllegalArgumentException("Informe a data de nascimento");
        }
        if (vazio(consulta.nome())) {
            throw new IllegalArgumentException("Informe o nome do paciente");
        }
        LocalDateTime inicio = exigirInicio(consulta.inicio());
        Agenda.Marcacao marcacao = new Agenda.Marcacao(null, consulta.profissionalId(), consulta.especialidadeId(),
                consulta.convenioId(), inicio, inicio.plusMinutes(duracao(consulta.duracaoMin())), texto(consulta.local()),
                new Agenda.Paciente(null, cpf, consulta.nascimento(), consulta.nome().trim(), texto(consulta.telefone())),
                Agenda.Origem.RECEPCAO, usuario, null, texto(consulta.codigoExterno()));
        return avisar(conta, null, agenda.marcar(conta, marcacao));
    }

    @Override
    public Agenda.Operacao confirmar(String conta, long agendamentoId, boolean jaFeitoNoSistema, String usuario) {
        String conversa = conversa(conta, agendamentoId);
        return avisar(conta, conversa, agenda.confirmar(conta, agendamentoId, alteracao(usuario, jaFeitoNoSistema)));
    }

    @Override
    public Agenda.Operacao cancelar(String conta, long agendamentoId, boolean jaFeitoNoSistema, String usuario) {
        String conversa = conversa(conta, agendamentoId);
        return avisar(conta, conversa, agenda.cancelar(conta, agendamentoId, alteracao(usuario, jaFeitoNoSistema)));
    }

    @Override
    public Agenda.Operacao remarcar(String conta, long agendamentoId, NovoHorario novo, boolean jaFeitoNoSistema, String usuario) {
        String conversa = conversa(conta, agendamentoId);
        LocalDateTime inicio = exigirInicio(novo.inicio());
        Agenda.Marcacao marcacao = new Agenda.Marcacao(null, novo.profissionalId(), novo.especialidadeId(), novo.convenioId(),
                inicio, inicio.plusMinutes(duracao(novo.duracaoMin())), texto(novo.local()), null, Agenda.Origem.RECEPCAO,
                usuario, null, texto(novo.codigoExterno()));
        return avisar(conta, conversa, agenda.remarcar(conta, agendamentoId, marcacao, alteracao(usuario, jaFeitoNoSistema)));
    }

    @Override
    public Agenda.Bloqueio ocupar(String conta, long profissionalId, LocalDateTime inicio, LocalDateTime fim, String usuario) {
        return bloquear(conta, new Agenda.Bloqueio(null, profissionalId, inicio, fim, OCUPADO_NO_SISTEMA, usuario), usuario);
    }

    @Override
    public Agenda.Bloqueio bloquear(String conta, Agenda.Bloqueio bloqueio, String usuario) {
        if (bloqueio.inicio() == null || bloqueio.fim() == null || !bloqueio.inicio().isBefore(bloqueio.fim())) {
            throw new IllegalArgumentException("O fim do bloqueio precisa ser depois do início");
        }
        return agenda.bloquear(conta, new Agenda.Bloqueio(null, bloqueio.profissionalId(), bloqueio.inicio(), bloqueio.fim(),
                texto(bloqueio.motivo()), usuario));
    }

    @Override
    public boolean desbloquear(String conta, long bloqueioId) {
        return agenda.desbloquear(conta, bloqueioId);
    }

    @Override
    public List<Agenda.Bloqueio> bloqueios(String conta, LocalDate de, LocalDate ate) {
        return agenda.bloqueios(conta, de.atStartOfDay(), ate.plusDays(1).atStartOfDay());
    }

    @Override
    public Map<Agenda.Cadastro, List<Agenda.Item>> cadastros(String conta) {
        Map<Agenda.Cadastro, List<Agenda.Item>> cadastros = new EnumMap<>(Agenda.Cadastro.class);
        for (Agenda.Cadastro cadastro : Agenda.Cadastro.values()) {
            cadastros.put(cadastro, agenda.itens(conta, cadastro));
        }
        return cadastros;
    }

    @Override
    public Agenda.Item salvarItem(String conta, Agenda.Cadastro cadastro, Agenda.Item item) {
        if (item == null || vazio(item.nome())) {
            throw new IllegalArgumentException("Informe o nome");
        }
        if (item.nome().trim().length() > 200) {
            throw new IllegalArgumentException("Nome com mais de 200 caracteres");
        }
        String nome = Textos.normalizar(item.nome()).replaceAll("\\s+", " ");
        boolean repetido = agenda.itens(conta, cadastro).stream()
                .anyMatch(outro -> !outro.id().equals(item.id())
                        && Textos.normalizar(outro.nome()).replaceAll("\\s+", " ").equals(nome));
        if (repetido) {
            throw new IllegalArgumentException("Já existe " + (cadastro == Agenda.Cadastro.ESPECIALIDADE ? "uma " : "um ")
                    + cadastro.titulo() + " com esse nome");
        }
        return agenda.salvarItem(conta, cadastro, item);
    }

    @Override
    public Agenda.Exclusao excluirItem(String conta, Agenda.Cadastro cadastro, long id) {
        return agenda.excluirItem(conta, cadastro, id);
    }

    @Override
    public List<Agenda.Grade> grades(String conta) {
        return agenda.grades(conta);
    }

    @Override
    public Agenda.Grade salvarGrade(String conta, Agenda.Grade grade) {
        if (grade.diaSemana() < 1 || grade.diaSemana() > 7) {
            throw new IllegalArgumentException("Dia da semana inválido");
        }
        if (grade.inicio() == null || grade.fim() == null || !grade.inicio().isBefore(grade.fim())) {
            throw new IllegalArgumentException("O fim do atendimento precisa ser depois do início");
        }
        duracao(grade.duracaoMin());
        return agenda.salvarGrade(conta, grade);
    }

    @Override
    public boolean removerGrade(String conta, long gradeId) {
        return agenda.removerGrade(conta, gradeId);
    }

    @Override
    public List<Agenda.Paciente> buscarPacientes(String conta, String termo) {
        return agenda.buscarPacientes(conta, termo);
    }

    @Override
    public HistoricoPaciente historicoPaciente(String conta, String cpf, LocalDate nascimento) {
        if (nascimento == null) {
            throw new IllegalArgumentException("Informe a data de nascimento");
        }
        return agenda.paciente(conta, cpf(cpf), nascimento)
                .map(p -> new HistoricoPaciente(p, agenda.agendamentosDoPaciente(conta, p.id())))
                .orElse(new HistoricoPaciente(null, List.of()));
    }

    @Override
    public List<Agenda.Pendencia> pendencias(String conta, Agenda.SituacaoPendencia situacao) {
        return agenda.pendencias(conta, situacao);
    }

    @Override
    public boolean concluirPendencia(String conta, long pendenciaId, String codigoExterno, String observacao, String usuario) {
        Agenda.Pendencia pendencia = agenda.pendencia(conta, pendenciaId)
                .orElseThrow(() -> new IllegalArgumentException("Pendência não encontrada"));
        if (vazio(codigoExterno) && (pendencia.tipo() == Agenda.TipoPendencia.LANCAR || pendencia.tipo() == Agenda.TipoPendencia.REMARCAR)) {
            throw new IllegalArgumentException("Informe o número do agendamento no sistema da clínica");
        }
        boolean concluida = agenda.concluirPendencia(conta, pendenciaId, usuario, codigoExterno, observacao);
        if (concluida) {
            aviso.concluida(conta, pendenciaId);
        }
        return concluida;
    }

    @Override
    public boolean reabrirPendencia(String conta, long pendenciaId, String usuario) {
        Agenda.Pendencia pendencia = agenda.pendencia(conta, pendenciaId)
                .orElseThrow(() -> new IllegalArgumentException("Pendência não encontrada"));
        boolean reaberta = agenda.reabrirPendencia(conta, pendenciaId, usuario);
        if (reaberta) {
            aviso.atualizada(conta, pendencia.conversaId());
        }
        return reaberta;
    }

    @Override
    public Agenda.ResumoPendencias resumo(String conta) {
        return agenda.resumo(conta, LocalDateTime.now(relogio));
    }

    @Override
    public List<Agenda.Agendamento> conferencia(String conta, LocalDate dia) {
        return agenda.agendamentos(conta, dia.atStartOfDay(), dia.plusDays(1).atStartOfDay(), true);
    }

    @Override
    public void conferir(String conta, long agendamentoId, Agenda.SituacaoConferencia situacao, String observacao, String usuario) {
        if (situacao == Agenda.SituacaoConferencia.DIVERGENTE && vazio(observacao)) {
            throw new IllegalArgumentException("Descreva a divergência (o que está diferente no sistema da clínica)");
        }
        agenda.conferir(conta, agendamentoId, situacao, observacao, usuario);
    }

    /** Pendência nova avisa a conversa; descarte (cancelou antes de lançar) atualiza a etiqueta. */
    private Agenda.Operacao avisar(String conta, String conversa, Agenda.Operacao operacao) {
        if (operacao.codigo() == CodigoNegocio.SUCESSO) {
            aviso.criadas(conta, operacao.pendencias());
            if (operacao.pendencias().isEmpty()) {
                aviso.atualizada(conta, conversa);
            }
        }
        return operacao;
    }

    private String conversa(String conta, long agendamentoId) {
        return agenda.agendamento(conta, agendamentoId).map(Agenda.Agendamento::conversaId).orElse(null);
    }

    private static Agenda.Alteracao alteracao(String usuario, boolean jaFeitoNoSistema) {
        return new Agenda.Alteracao(null, null, Agenda.Origem.RECEPCAO, usuario, jaFeitoNoSistema);
    }

    private Map<Long, String> nomes(String conta, Agenda.Cadastro cadastro) {
        return agenda.itens(conta, cadastro).stream().collect(Collectors.toMap(Agenda.Item::id, Agenda.Item::nome, (a, b) -> a));
    }

    private static LocalDateTime exigirInicio(LocalDateTime inicio) {
        if (inicio == null) {
            throw new IllegalArgumentException("Informe data e hora");
        }
        return inicio.withSecond(0).withNano(0);
    }

    private static int duracao(int minutos) {
        if (minutos < 5 || minutos > 480) {
            throw new IllegalArgumentException("Duração entre 5 e 480 minutos");
        }
        return minutos;
    }

    static String cpf(String texto) {
        String digitos = texto == null ? "" : texto.replaceAll("\\D", "");
        if (digitos.length() != 11) {
            throw new IllegalArgumentException("CPF precisa ter 11 dígitos");
        }
        return digitos;
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor.trim();
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}
