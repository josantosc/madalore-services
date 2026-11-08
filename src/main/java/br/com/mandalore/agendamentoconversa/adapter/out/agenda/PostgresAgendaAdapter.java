package br.com.mandalore.agendamentoconversa.adapter.out.agenda;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaMandalorePort;
import br.com.mandalore.agendamentoconversa.application.domain.service.TextoPendencia;

@Component
@ConditionalOnProperty(name = {"agenda.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PostgresAgendaAdapter implements AgendaMandalorePort {

    static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private static final String AGENDAMENTOS = """
            SELECT a.id, a.profissional_id, p.nome AS profissional, a.especialidade_id, e.nome AS especialidade,
                   a.convenio_id, c.nome AS convenio, a.paciente_id, pa.cpf, pa.nascimento, pa.nome AS paciente,
                   pa.telefone, a.inicio, a.fim, a.local, a.status, a.origem, a.codigo_externo, a.conversa_id,
                   a.remarcado_de, cf.situacao AS conferencia,
                   (SELECT string_agg(pe.tipo, ',' ORDER BY pe.id) FROM agenda.pendencia pe
                    WHERE pe.agendamento_id = a.id AND pe.situacao = 'ABERTA') AS pendencias
            FROM agenda.agendamento a
            JOIN agenda.profissional p ON p.id = a.profissional_id
            JOIN agenda.especialidade e ON e.id = a.especialidade_id
            LEFT JOIN agenda.convenio c ON c.id = a.convenio_id
            JOIN agenda.paciente pa ON pa.id = a.paciente_id
            LEFT JOIN agenda.conferencia cf ON cf.agendamento_id = a.id
            WHERE a.conta_id = :conta
            """;

    private static final String PENDENCIAS = """
            SELECT pe.id, pe.agendamento_id, pe.tipo, pe.situacao, pe.resumo, pe.prazo, pe.criada_em, pe.concluida_em,
                   pe.concluida_por, pe.codigo_externo, pe.observacao, a.conversa_id, a.inicio, pa.nome AS paciente
            FROM agenda.pendencia pe
            JOIN agenda.agendamento a ON a.id = pe.agendamento_id
            JOIN agenda.paciente pa ON pa.id = a.paciente_id
            WHERE pe.conta_id = :conta
            """;

    private static final String ATIVO = "status IN ('MARCADO', 'CONFIRMADO')";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final Clock relogio;
    private final int prazoMinutos;
    private final boolean pendenciaConfirmacao;

    @Autowired
    public PostgresAgendaAdapter(
            @Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Qualifier("observabilityDataSource") DataSource dataSource,
            @Value("${agenda.prazo-minutos:30}") int prazoMinutos,
            @Value("${agenda.pendencia-confirmacao:false}") boolean pendenciaConfirmacao
    ) {
        this(jdbc, dataSource, Clock.system(ZONA), prazoMinutos, pendenciaConfirmacao);
    }

    PostgresAgendaAdapter(
            NamedParameterJdbcTemplate jdbc,
            DataSource dataSource,
            Clock relogio,
            int prazoMinutos,
            boolean pendenciaConfirmacao
    ) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.relogio = relogio;
        this.prazoMinutos = prazoMinutos;
        this.pendenciaConfirmacao = pendenciaConfirmacao;
    }

    @Override
    public List<String> contas() {
        return jdbc.queryForList("""
                SELECT conta_id FROM agenda.profissional
                UNION SELECT conta_id FROM agenda.agendamento
                ORDER BY 1
                """, new MapSqlParameterSource(), String.class);
    }

    @Override
    public List<Agenda.Item> itens(String conta, Agenda.Cadastro cadastro) {
        return jdbc.query("SELECT id, nome, ativo FROM " + tabela(cadastro) + " WHERE conta_id = :conta ORDER BY ativo DESC, nome",
                conta(conta), (rs, i) -> new Agenda.Item(rs.getLong("id"), rs.getString("nome"), rs.getBoolean("ativo")));
    }

    @Override
    public Agenda.Item salvarItem(String conta, Agenda.Cadastro cadastro, Agenda.Item item) {
        MapSqlParameterSource params = conta(conta).addValue("id", item.id()).addValue("nome", item.nome().trim())
                .addValue("ativo", item.ativo());
        Long id = item.id() == null
                ? jdbc.queryForObject("INSERT INTO " + tabela(cadastro) + " (conta_id, nome, ativo) VALUES (:conta, :nome, :ativo) RETURNING id",
                        params, Long.class)
                : jdbc.update("UPDATE " + tabela(cadastro) + " SET nome = :nome, ativo = :ativo WHERE id = :id AND conta_id = :conta", params) == 1
                        ? item.id() : null;
        if (id == null) {
            throw new IllegalArgumentException("Cadastro não encontrado");
        }
        return new Agenda.Item(id, item.nome().trim(), item.ativo());
    }

    @Override
    public Agenda.Exclusao excluirItem(String conta, Agenda.Cadastro cadastro, long id) {
        String tabela = tabela(cadastro);
        String coluna = coluna(cadastro);
        MapSqlParameterSource params = conta(conta).addValue("id", id);
        return transacao.execute(status -> {
            Long existe = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE id = :id AND conta_id = :conta",
                    params, Long.class);
            if (existe == null || existe == 0) {
                return Agenda.Exclusao.NAO_ENCONTRADO;
            }
            Long consultas = jdbc.queryForObject("SELECT COUNT(*) FROM agenda.agendamento WHERE " + coluna + " = :id",
                    params, Long.class);
            boolean comGrade = cadastro != Agenda.Cadastro.CONVENIO;
            if (consultas != null && consultas > 0) {
                jdbc.update("UPDATE " + tabela + " SET ativo = FALSE WHERE id = :id AND conta_id = :conta", params);
                if (comGrade) {
                    jdbc.update("UPDATE agenda.grade SET ativo = FALSE WHERE " + coluna + " = :id AND conta_id = :conta", params);
                }
                return Agenda.Exclusao.DESATIVADO;
            }
            if (comGrade) {
                jdbc.update("DELETE FROM agenda.grade WHERE " + coluna + " = :id AND conta_id = :conta", params);
                jdbc.update("DELETE FROM agenda.profissional_especialidade WHERE " + coluna + " = :id", params);
            }
            if (cadastro == Agenda.Cadastro.PROFISSIONAL) {
                jdbc.update("DELETE FROM agenda.bloqueio WHERE profissional_id = :id AND conta_id = :conta", params);
            }
            jdbc.update("DELETE FROM " + tabela + " WHERE id = :id AND conta_id = :conta", params);
            return Agenda.Exclusao.EXCLUIDO;
        });
    }

    @Override
    public Map<Long, List<Long>> especialidadesDosProfissionais(String conta) {
        Map<Long, List<Long>> vinculos = new LinkedHashMap<>();
        jdbc.query("""
                SELECT pe.profissional_id, pe.especialidade_id
                FROM agenda.profissional_especialidade pe
                JOIN agenda.profissional p ON p.id = pe.profissional_id
                JOIN agenda.especialidade e ON e.id = pe.especialidade_id
                WHERE p.conta_id = :conta
                ORDER BY pe.profissional_id, e.nome
                """, conta(conta), rs -> {
            vinculos.computeIfAbsent(rs.getLong("profissional_id"), p -> new ArrayList<>()).add(rs.getLong("especialidade_id"));
        });
        return vinculos;
    }

    @Override
    public void vincularEspecialidades(String conta, long profissionalId, List<Long> especialidades) {
        exigirDaConta(conta, "agenda.profissional", profissionalId);
        especialidades.forEach(e -> exigirDaConta(conta, "agenda.especialidade", e));
        transacao.executeWithoutResult(status -> {
            MapSqlParameterSource params = new MapSqlParameterSource("profissional", profissionalId);
            jdbc.update("DELETE FROM agenda.profissional_especialidade WHERE profissional_id = :profissional", params);
            for (Long especialidade : especialidades) {
                jdbc.update("INSERT INTO agenda.profissional_especialidade (profissional_id, especialidade_id) VALUES (:profissional, :especialidade)",
                        new MapSqlParameterSource(params.getValues()).addValue("especialidade", especialidade));
            }
        });
    }

    @Override
    public List<Agenda.Grade> grades(String conta) {
        return jdbc.query("SELECT * FROM agenda.grade WHERE conta_id = :conta ORDER BY data NULLS FIRST, dia_semana, inicio, profissional_id",
                conta(conta), this::grade);
    }

    @Override
    public Optional<Agenda.Grade> grade(String conta, long id) {
        return jdbc.query("SELECT * FROM agenda.grade WHERE conta_id = :conta AND id = :id",
                conta(conta).addValue("id", id), this::grade).stream().findFirst();
    }

    @Override
    public Agenda.Grade salvarGrade(String conta, Agenda.Grade grade) {
        MapSqlParameterSource params = conta(conta)
                .addValue("id", grade.id())
                .addValue("profissional", grade.profissionalId())
                .addValue("especialidade", grade.especialidadeId())
                .addValue("dia", grade.diaSemana(), Types.SMALLINT)
                .addValue("data", grade.data(), Types.DATE)
                .addValue("vigenciaInicio", grade.vigenciaInicio(), Types.DATE)
                .addValue("vigenciaFim", grade.vigenciaFim(), Types.DATE)
                .addValue("inicio", Time.valueOf(grade.inicio()))
                .addValue("fim", Time.valueOf(grade.fim()))
                .addValue("duracao", grade.duracaoMin())
                .addValue("bot", grade.ofereceBot())
                .addValue("local", grade.local() == null ? "" : grade.local().trim())
                .addValue("ativo", grade.ativo());
        exigirDaConta(conta, "agenda.profissional", grade.profissionalId());
        exigirDaConta(conta, "agenda.especialidade", grade.especialidadeId());
        Long id;
        if (grade.id() == null) {
            id = jdbc.queryForObject("""
                    INSERT INTO agenda.grade (conta_id, profissional_id, especialidade_id, dia_semana, data, vigencia_inicio,
                                              vigencia_fim, inicio, fim, duracao_min, oferece_bot, local, ativo)
                    VALUES (:conta, :profissional, :especialidade, :dia, :data, :vigenciaInicio, :vigenciaFim, :inicio, :fim,
                            :duracao, :bot, :local, :ativo)
                    RETURNING id
                    """, params, Long.class);
        } else if (jdbc.update("""
                UPDATE agenda.grade SET profissional_id = :profissional, especialidade_id = :especialidade, dia_semana = :dia,
                       data = :data, vigencia_inicio = :vigenciaInicio, vigencia_fim = :vigenciaFim, inicio = :inicio, fim = :fim, duracao_min = :duracao, oferece_bot = :bot, local = :local, ativo = :ativo
                WHERE id = :id AND conta_id = :conta
                """, params) == 1) {
            id = grade.id();
        } else {
            throw new IllegalArgumentException("Horário de atendimento não encontrado");
        }
        return grade(conta, id).orElseThrow();
    }

    @Override
    public boolean removerGrade(String conta, long id) {
        return jdbc.update("DELETE FROM agenda.grade WHERE id = :id AND conta_id = :conta", conta(conta).addValue("id", id)) == 1;
    }

    @Override
    public List<Agenda.Bloqueio> bloqueios(String conta, LocalDateTime de, LocalDateTime ate) {
        return jdbc.query("""
                SELECT * FROM agenda.bloqueio
                WHERE conta_id = :conta AND inicio < :ate AND fim > :de
                ORDER BY inicio
                """, conta(conta).addValue("de", Timestamp.valueOf(de)).addValue("ate", Timestamp.valueOf(ate)), this::bloqueio);
    }

    @Override
    public Agenda.Bloqueio bloquear(String conta, Agenda.Bloqueio bloqueio) {
        if (bloqueio.profissionalId() != null) {
            exigirDaConta(conta, "agenda.profissional", bloqueio.profissionalId());
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO agenda.bloqueio (conta_id, profissional_id, inicio, fim, motivo, criado_por)
                VALUES (:conta, :profissional, :inicio, :fim, :motivo, :usuario)
                RETURNING id
                """, conta(conta)
                .addValue("profissional", bloqueio.profissionalId())
                .addValue("inicio", Timestamp.valueOf(bloqueio.inicio()))
                .addValue("fim", Timestamp.valueOf(bloqueio.fim()))
                .addValue("motivo", bloqueio.motivo() == null ? "" : bloqueio.motivo().trim())
                .addValue("usuario", bloqueio.criadoPor()), Long.class);
        return new Agenda.Bloqueio(id, bloqueio.profissionalId(), bloqueio.inicio(), bloqueio.fim(), bloqueio.motivo(), bloqueio.criadoPor());
    }

    @Override
    public boolean desbloquear(String conta, long id) {
        return jdbc.update("DELETE FROM agenda.bloqueio WHERE id = :id AND conta_id = :conta", conta(conta).addValue("id", id)) == 1;
    }

    @Override
    public Optional<Agenda.Paciente> paciente(String conta, String cpf, LocalDate nascimento) {
        return jdbc.query("SELECT * FROM agenda.paciente WHERE conta_id = :conta AND cpf = :cpf AND nascimento = :nascimento",
                conta(conta).addValue("cpf", cpf).addValue("nascimento", Date.valueOf(nascimento)), this::paciente).stream().findFirst();
    }

    @Override
    public List<Agenda.Paciente> buscarPacientes(String conta, String termo) {
        String limpo = termo == null ? "" : termo.trim();
        String digitos = limpo.replaceAll("\\D", "");
        return jdbc.query("""
                SELECT * FROM agenda.paciente
                WHERE conta_id = :conta
                AND ((:digitos <> '' AND cpf LIKE :digitos || '%') OR (:nome <> '' AND nome ILIKE '%' || :nome || '%'))
                ORDER BY nome
                LIMIT 20
                """, conta(conta).addValue("digitos", digitos.length() >= 3 ? digitos : "")
                .addValue("nome", digitos.isEmpty() && limpo.length() >= 2 ? limpo : ""), this::paciente);
    }

    @Override
    public List<Agenda.Agendamento> agendamentos(String conta, LocalDateTime de, LocalDateTime ate, boolean ativos) {
        return jdbc.query(AGENDAMENTOS + " AND a.inicio >= :de AND a.inicio < :ate" + (ativos ? " AND a." + ATIVO : "")
                        + " ORDER BY a.inicio, p.nome",
                conta(conta).addValue("de", Timestamp.valueOf(de)).addValue("ate", Timestamp.valueOf(ate)), this::agendamento);
    }

    @Override
    public List<Agenda.Agendamento> agendamentosDoPaciente(String conta, long pacienteId) {
        return jdbc.query(AGENDAMENTOS + " AND a.paciente_id = :paciente ORDER BY a.inicio DESC",
                conta(conta).addValue("paciente", pacienteId), this::agendamento);
    }

    @Override
    public Optional<Agenda.Agendamento> agendamento(String conta, long id) {
        return jdbc.query(AGENDAMENTOS + " AND a.id = :id", conta(conta).addValue("id", id), this::agendamento).stream().findFirst();
    }

    @Override
    public List<Agenda.Evento> historico(String conta, long agendamentoId) {
        return jdbc.query("""
                SELECT ev.tipo, ev.usuario, ev.detalhe, ev.criado_em
                FROM agenda.agendamento_evento ev
                JOIN agenda.agendamento a ON a.id = ev.agendamento_id
                WHERE a.conta_id = :conta AND ev.agendamento_id = :id
                ORDER BY ev.criado_em, ev.id
                """, conta(conta).addValue("id", agendamentoId), (rs, i) -> new Agenda.Evento(
                rs.getString("tipo"), rs.getString("usuario"), rs.getString("detalhe"), instante(rs, "criado_em")));
    }

    @Override
    public Agenda.Operacao marcar(String conta, Agenda.Marcacao marcacao) {
        return transacao.execute(status -> {
            Agenda.Operacao repetida = repetida(marcacao.chave());
            if (repetida != null) {
                return repetida;
            }
            travarProfissional(conta, marcacao.profissionalId());
            if (sobrepoe(conta, marcacao.profissionalId(), marcacao.inicio(), marcacao.fim(), null)) {
                return registrar(marcacao.chave(), Agenda.Operacao.falha(CodigoNegocio.HORARIO_INDISPONIVEL));
            }
            long pacienteId = gravarPaciente(conta, marcacao.paciente());
            long id = inserir(conta, marcacao, pacienteId, null);
            evento(id, "MARCADO", marcacao.usuario(), origem(marcacao.origem()) + " " + TextoPendencia.dataHora(marcacao.inicio()));
            List<Agenda.Pendencia> pendencias = new ArrayList<>();
            if (vazio(marcacao.codigoExterno())) {
                pendencias.add(criarPendencia(conta, Agenda.TipoPendencia.LANCAR, id, null, ""));
            } else {
                evento(id, "LANCADO", marcacao.usuario(), "Código " + marcacao.codigoExterno().trim());
            }
            return registrar(marcacao.chave(), new Agenda.Operacao(CodigoNegocio.SUCESSO, id, pendencias));
        });
    }

    @Override
    public Agenda.Operacao confirmar(String conta, long agendamentoId, Agenda.Alteracao alteracao) {
        return transacao.execute(status -> {
            Agenda.Operacao repetida = repetida(alteracao.chave());
            if (repetida != null) {
                return repetida;
            }
            int alterados = jdbc.update("""
                    UPDATE agenda.agendamento SET status = 'CONFIRMADO', atualizado_em = CURRENT_TIMESTAMP
                    WHERE id = :id AND conta_id = :conta AND\s""" + ATIVO + """
                     AND (CAST(:paciente AS BIGINT) IS NULL OR paciente_id = CAST(:paciente AS BIGINT))
                    """, alvo(conta, agendamentoId, alteracao));
            if (alterados != 1) {
                return registrar(alteracao.chave(), Agenda.Operacao.falha(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO));
            }
            evento(agendamentoId, "CONFIRMADO", alteracao.usuario(), origem(alteracao.origem()));
            List<Agenda.Pendencia> pendencias = new ArrayList<>();
            if (pendenciaConfirmacao && !alteracao.jaFeitoNoSistema()
                    && abertas(agendamentoId, "'LANCAR', 'REMARCAR', 'CONFIRMAR'") == 0) {
                pendencias.add(criarPendencia(conta, Agenda.TipoPendencia.CONFIRMAR, agendamentoId, null, ""));
            }
            return registrar(alteracao.chave(), new Agenda.Operacao(CodigoNegocio.SUCESSO, agendamentoId, pendencias));
        });
    }

    @Override
    public Agenda.Operacao cancelar(String conta, long agendamentoId, Agenda.Alteracao alteracao) {
        return transacao.execute(status -> {
            Agenda.Operacao repetida = repetida(alteracao.chave());
            if (repetida != null) {
                return repetida;
            }
            Optional<Agenda.Agendamento> atual = agendamento(conta, agendamentoId);
            int alterados = jdbc.update("""
                    UPDATE agenda.agendamento SET status = 'CANCELADO', atualizado_em = CURRENT_TIMESTAMP
                    WHERE id = :id AND conta_id = :conta AND\s""" + ATIVO + """
                     AND (CAST(:paciente AS BIGINT) IS NULL OR paciente_id = CAST(:paciente AS BIGINT))
                    """, alvo(conta, agendamentoId, alteracao));
            if (alterados != 1 || atual.isEmpty()) {
                return registrar(alteracao.chave(), Agenda.Operacao.falha(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO));
            }
            evento(agendamentoId, "CANCELADO", alteracao.usuario(), origem(alteracao.origem()));
            List<String> descartadas = descartar(agendamentoId, alteracao.usuario(), "Agendamento cancelado antes do lançamento");
            List<Agenda.Pendencia> pendencias = new ArrayList<>();
            if (!descartadas.contains("LANCAR") && !alteracao.jaFeitoNoSistema()) {
                Long anterior = descartadas.contains("REMARCAR") ? atual.get().remarcadoDe() : null;
                pendencias.add(criarPendencia(conta, Agenda.TipoPendencia.CANCELAR, agendamentoId, anterior, ""));
            }
            return registrar(alteracao.chave(), new Agenda.Operacao(CodigoNegocio.SUCESSO, agendamentoId, pendencias));
        });
    }

    @Override
    public Agenda.Operacao remarcar(String conta, long agendamentoId, Agenda.Marcacao nova, Agenda.Alteracao alteracao) {
        return transacao.execute(status -> {
            Agenda.Operacao repetida = repetida(alteracao.chave());
            if (repetida != null) {
                return repetida;
            }
            Optional<Agenda.Agendamento> encontrado = agendamento(conta, agendamentoId)
                    .filter(a -> a.status().ativo())
                    .filter(a -> alteracao.pacienteId() == null || alteracao.pacienteId().equals(a.paciente().id()));
            if (encontrado.isEmpty()) {
                return registrar(alteracao.chave(), Agenda.Operacao.falha(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO));
            }
            Agenda.Agendamento atual = encontrado.get();
            travarProfissional(conta, nova.profissionalId());
            if (sobrepoe(conta, nova.profissionalId(), nova.inicio(), nova.fim(), agendamentoId)) {
                return registrar(alteracao.chave(), Agenda.Operacao.falha(CodigoNegocio.HORARIO_INDISPONIVEL));
            }
            jdbc.update("UPDATE agenda.agendamento SET status = 'REMARCADO', atualizado_em = CURRENT_TIMESTAMP WHERE id = :id",
                    new MapSqlParameterSource("id", agendamentoId));
            Agenda.Marcacao completa = new Agenda.Marcacao(nova.chave(), nova.profissionalId(), nova.especialidadeId(),
                    nova.convenioId() == null ? atual.convenioId() : nova.convenioId(), nova.inicio(), nova.fim(), nova.local(),
                    atual.paciente(), nova.origem(), nova.usuario(),
                    nova.conversaId() == null ? atual.conversaId() : nova.conversaId(), nova.codigoExterno());
            long id = inserir(conta, completa, atual.paciente().id(), agendamentoId);
            evento(agendamentoId, "REMARCADO_PARA", alteracao.usuario(), TextoPendencia.dataHora(nova.inicio()));
            evento(id, "REMARCADO_DE", alteracao.usuario(), origem(alteracao.origem()) + " " + TextoPendencia.dataHora(atual.inicio()));
            List<String> descartadas = descartar(agendamentoId, alteracao.usuario(), "Agendamento remarcado antes do lançamento");
            List<Agenda.Pendencia> pendencias = new ArrayList<>();
            boolean jaFeito = alteracao.jaFeitoNoSistema() || !vazio(nova.codigoExterno());
            if (!vazio(nova.codigoExterno())) {
                evento(id, "LANCADO", alteracao.usuario(), "Código " + nova.codigoExterno().trim());
            }
            if (descartadas.contains("LANCAR")) {
                if (!jaFeito) {
                    pendencias.add(criarPendencia(conta, Agenda.TipoPendencia.LANCAR, id, null, ""));
                }
            } else if (!jaFeito) {
                Long anterior = descartadas.contains("REMARCAR") ? atual.remarcadoDe() : agendamentoId;
                pendencias.add(criarPendencia(conta, Agenda.TipoPendencia.REMARCAR, id, anterior, ""));
            }
            return registrar(alteracao.chave(), new Agenda.Operacao(CodigoNegocio.SUCESSO, id, pendencias));
        });
    }

    @Override
    public List<Agenda.Pendencia> pendencias(String conta, Agenda.SituacaoPendencia situacao) {
        String filtro = situacao == null
                ? " AND (pe.situacao = 'ABERTA' OR pe.concluida_em >= CURRENT_TIMESTAMP - INTERVAL '7 days')"
                : " AND pe.situacao = :situacao";
        return jdbc.query(PENDENCIAS + filtro
                        + " ORDER BY CASE pe.situacao WHEN 'ABERTA' THEN 0 ELSE 1 END, pe.prazo, pe.id LIMIT 500",
                conta(conta).addValue("situacao", situacao == null ? null : situacao.name()), this::pendencia);
    }

    @Override
    public Optional<Agenda.Pendencia> pendencia(String conta, long id) {
        return jdbc.query(PENDENCIAS + " AND pe.id = :id", conta(conta).addValue("id", id), this::pendencia).stream().findFirst();
    }

    @Override
    public boolean concluirPendencia(String conta, long id, String usuario, String codigoExterno, String observacao) {
        Boolean concluida = transacao.execute(status -> {
            String codigo = vazio(codigoExterno) ? null : codigoExterno.trim();
            List<Long> agendamento = jdbc.queryForList("""
                    UPDATE agenda.pendencia
                    SET situacao = 'CONCLUIDA', concluida_em = CURRENT_TIMESTAMP, concluida_por = :usuario,
                        codigo_externo = :codigo, observacao = :observacao
                    WHERE id = :id AND conta_id = :conta AND situacao = 'ABERTA'
                    RETURNING agendamento_id
                    """, conta(conta).addValue("id", id).addValue("usuario", usuario).addValue("codigo", codigo)
                    .addValue("observacao", observacao == null ? "" : observacao.trim()), Long.class);
            if (agendamento.isEmpty()) {
                return false;
            }
            if (codigo != null) {
                jdbc.update("UPDATE agenda.agendamento SET codigo_externo = :codigo, atualizado_em = CURRENT_TIMESTAMP WHERE id = :id",
                        new MapSqlParameterSource("codigo", codigo).addValue("id", agendamento.get(0)));
            }
            evento(agendamento.get(0), "PENDENCIA_CONCLUIDA", usuario, "Pendência " + id + (codigo == null ? "" : ", código " + codigo));
            return true;
        });
        return Boolean.TRUE.equals(concluida);
    }

    @Override
    public boolean reabrirPendencia(String conta, long id, String usuario) {
        Boolean reaberta = transacao.execute(status -> {
            List<Long> agendamento = jdbc.queryForList("""
                    UPDATE agenda.pendencia
                    SET situacao = 'ABERTA', concluida_em = NULL, concluida_por = NULL
                    WHERE id = :id AND conta_id = :conta AND situacao = 'CONCLUIDA'
                    RETURNING agendamento_id
                    """, conta(conta).addValue("id", id), Long.class);
            if (agendamento.isEmpty()) {
                return false;
            }
            evento(agendamento.get(0), "PENDENCIA_REABERTA", usuario, "Pendência " + id);
            return true;
        });
        return Boolean.TRUE.equals(reaberta);
    }

    @Override
    public long pendenciasAbertasDaConversa(String conta, String conversaId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agenda.pendencia pe
                JOIN agenda.agendamento a ON a.id = pe.agendamento_id
                WHERE pe.conta_id = :conta AND pe.situacao = 'ABERTA' AND a.conversa_id = :conversa
                """, conta(conta).addValue("conversa", conversaId), Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public Agenda.ResumoPendencias resumo(String conta, LocalDateTime agora) {
        MapSqlParameterSource params = new MapSqlParameterSource("conta", conta)
                .addValue("agora", Timestamp.valueOf(agora))
                .addValue("amanha", Timestamp.valueOf(agora.toLocalDate().plusDays(1).atStartOfDay()))
                .addValue("depois", Timestamp.valueOf(agora.toLocalDate().plusDays(2).atStartOfDay()));
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE situacao = 'ABERTA') AS abertas,
                       COUNT(*) FILTER (WHERE situacao = 'ABERTA' AND prazo < :agora) AS atrasadas,
                       MIN(prazo) FILTER (WHERE situacao = 'ABERTA') AS mais_antigo,
                       COUNT(*) FILTER (WHERE situacao = 'ABERTA' AND tipo = 'CONFERIR') AS divergencias,
                       (SELECT COUNT(*) FROM agenda.agendamento a
                        WHERE (CAST(:conta AS VARCHAR) IS NULL OR a.conta_id = CAST(:conta AS VARCHAR))
                        AND a.status IN ('MARCADO', 'CONFIRMADO') AND a.inicio >= :amanha AND a.inicio < :depois
                        AND NOT EXISTS (SELECT 1 FROM agenda.conferencia cf WHERE cf.agendamento_id = a.id)) AS sem_conferencia
                FROM agenda.pendencia
                WHERE CAST(:conta AS VARCHAR) IS NULL OR conta_id = CAST(:conta AS VARCHAR)
                """, params, (rs, i) -> new Agenda.ResumoPendencias(
                rs.getLong("abertas"), rs.getLong("atrasadas"), dataHora(rs, "mais_antigo"),
                rs.getLong("divergencias"), rs.getLong("sem_conferencia")));
    }

    @Override
    public void conferir(String conta, long agendamentoId, Agenda.SituacaoConferencia situacao, String observacao, String usuario) {
        transacao.executeWithoutResult(status -> {
            if (agendamento(conta, agendamentoId).isEmpty()) {
                throw new IllegalArgumentException("Agendamento não encontrado");
            }
            String nota = observacao == null ? "" : observacao.trim();
            jdbc.update("""
                    INSERT INTO agenda.conferencia (agendamento_id, situacao, observacao, usuario)
                    VALUES (:id, :situacao, :observacao, :usuario)
                    ON CONFLICT (agendamento_id) DO UPDATE SET situacao = EXCLUDED.situacao, observacao = EXCLUDED.observacao,
                        usuario = EXCLUDED.usuario, criado_em = CURRENT_TIMESTAMP
                    """, new MapSqlParameterSource("id", agendamentoId).addValue("situacao", situacao.name())
                    .addValue("observacao", nota).addValue("usuario", usuario));
            evento(agendamentoId, situacao.name(), usuario, nota);
            if (situacao == Agenda.SituacaoConferencia.DIVERGENTE && abertas(agendamentoId, "'CONFERIR'") == 0) {
                criarPendencia(conta, Agenda.TipoPendencia.CONFERIR, agendamentoId, null, nota);
            }
        });
    }

    private Agenda.Operacao repetida(String chave) {
        if (vazio(chave)) {
            return null;
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:chave))", new MapSqlParameterSource("chave", "agenda:" + chave),
                (ResultSetExtractor<Void>) rs -> null);
        List<String> codigos = jdbc.queryForList("SELECT codigo FROM agenda.operacao WHERE chave = :chave",
                new MapSqlParameterSource("chave", chave), String.class);
        return codigos.isEmpty() ? null : Agenda.Operacao.falha(CodigoNegocio.valueOf(codigos.get(0)));
    }

    private Agenda.Operacao registrar(String chave, Agenda.Operacao operacao) {
        if (!vazio(chave)) {
            jdbc.update("INSERT INTO agenda.operacao (chave, codigo) VALUES (:chave, :codigo) ON CONFLICT (chave) DO NOTHING",
                    new MapSqlParameterSource("chave", chave).addValue("codigo", operacao.codigo().name()));
        }
        return operacao;
    }

    private void travarProfissional(String conta, long profissionalId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:chave))",
                new MapSqlParameterSource("chave", "agenda:" + conta + ":profissional:" + profissionalId),
                (ResultSetExtractor<Void>) rs -> null);
    }

    private boolean sobrepoe(String conta, long profissionalId, LocalDateTime inicio, LocalDateTime fim, Long ignorar) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agenda.agendamento
                WHERE conta_id = :conta AND profissional_id = :profissional AND\s""" + ATIVO + """
                 AND inicio < :fim AND fim > :inicio AND (CAST(:ignorar AS BIGINT) IS NULL OR id <> CAST(:ignorar AS BIGINT))
                """, conta(conta).addValue("profissional", profissionalId).addValue("inicio", Timestamp.valueOf(inicio))
                .addValue("fim", Timestamp.valueOf(fim)).addValue("ignorar", ignorar), Long.class);
        return total != null && total > 0;
    }

    private long gravarPaciente(String conta, Agenda.Paciente paciente) {
        return jdbc.queryForObject("""
                INSERT INTO agenda.paciente (conta_id, cpf, nascimento, nome, telefone)
                VALUES (:conta, :cpf, :nascimento, :nome, :telefone)
                ON CONFLICT (conta_id, cpf, nascimento) DO UPDATE SET
                    nome = CASE WHEN EXCLUDED.nome = '' THEN agenda.paciente.nome ELSE EXCLUDED.nome END,
                    telefone = CASE WHEN EXCLUDED.telefone = '' THEN agenda.paciente.telefone ELSE EXCLUDED.telefone END
                RETURNING id
                """, conta(conta)
                .addValue("cpf", paciente.cpf())
                .addValue("nascimento", Date.valueOf(paciente.nascimento()))
                .addValue("nome", paciente.nome() == null ? "" : paciente.nome().trim())
                .addValue("telefone", paciente.telefone() == null ? "" : paciente.telefone().trim()), Long.class);
    }

    private long inserir(String conta, Agenda.Marcacao marcacao, long pacienteId, Long remarcadoDe) {
        return jdbc.queryForObject("""
                INSERT INTO agenda.agendamento (conta_id, profissional_id, especialidade_id, convenio_id, paciente_id, inicio, fim,
                                                local, status, origem, codigo_externo, conversa_id, remarcado_de)
                VALUES (:conta, :profissional, :especialidade, :convenio, :paciente, :inicio, :fim, :local, 'MARCADO', :origem,
                        :codigo, :conversa, :remarcadoDe)
                RETURNING id
                """, conta(conta)
                .addValue("profissional", marcacao.profissionalId())
                .addValue("especialidade", marcacao.especialidadeId())
                .addValue("convenio", marcacao.convenioId())
                .addValue("paciente", pacienteId)
                .addValue("inicio", Timestamp.valueOf(marcacao.inicio()))
                .addValue("fim", Timestamp.valueOf(marcacao.fim()))
                .addValue("local", marcacao.local() == null ? "" : marcacao.local())
                .addValue("origem", marcacao.origem().name())
                .addValue("codigo", vazio(marcacao.codigoExterno()) ? null : marcacao.codigoExterno().trim())
                .addValue("conversa", vazio(marcacao.conversaId()) ? null : marcacao.conversaId())
                .addValue("remarcadoDe", remarcadoDe), Long.class);
    }

    /** Descarta as pendências abertas do agendamento e devolve os tipos descartados. */
    private List<String> descartar(long agendamentoId, String usuario, String motivo) {
        return jdbc.queryForList("""
                UPDATE agenda.pendencia
                SET situacao = 'DESCARTADA', concluida_em = CURRENT_TIMESTAMP, concluida_por = :usuario, observacao = :motivo
                WHERE agendamento_id = :id AND situacao = 'ABERTA' AND tipo <> 'CONFERIR'
                RETURNING tipo
                """, new MapSqlParameterSource("id", agendamentoId).addValue("usuario", usuario).addValue("motivo", motivo), String.class);
    }

    private long abertas(long agendamentoId, String tipos) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM agenda.pendencia WHERE agendamento_id = :id AND situacao = 'ABERTA' AND tipo IN ("
                + tipos + ")", new MapSqlParameterSource("id", agendamentoId), Long.class);
        return total == null ? 0 : total;
    }

    private Agenda.Pendencia criarPendencia(String conta, Agenda.TipoPendencia tipo, long agendamentoId, Long anteriorId, String observacao) {
        Agenda.Agendamento agendamento = agendamento(conta, agendamentoId).orElseThrow();
        Agenda.Agendamento anterior = anteriorId == null ? null : agendamento(conta, anteriorId).orElse(null);
        Long id = jdbc.queryForObject("""
                INSERT INTO agenda.pendencia (conta_id, agendamento_id, tipo, resumo, prazo, observacao)
                VALUES (:conta, :agendamento, :tipo, :resumo, :prazo, :observacao)
                RETURNING id
                """, conta(conta)
                .addValue("agendamento", agendamentoId)
                .addValue("tipo", tipo.name())
                .addValue("resumo", TextoPendencia.resumo(tipo, agendamento, anterior, observacao))
                .addValue("prazo", Timestamp.valueOf(LocalDateTime.now(relogio).plusMinutes(prazoMinutos)))
                .addValue("observacao", observacao == null ? "" : observacao), Long.class);
        return pendencia(conta, id).orElseThrow();
    }

    private void evento(long agendamentoId, String tipo, String usuario, String detalhe) {
        jdbc.update("INSERT INTO agenda.agendamento_evento (agendamento_id, tipo, usuario, detalhe) VALUES (:id, :tipo, :usuario, :detalhe)",
                new MapSqlParameterSource("id", agendamentoId).addValue("tipo", tipo)
                        .addValue("usuario", vazio(usuario) ? "sistema" : usuario)
                        .addValue("detalhe", detalhe == null ? "" : detalhe.length() > 1000 ? detalhe.substring(0, 1000) : detalhe));
    }

    private void exigirDaConta(String conta, String tabela, long id) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE id = :id AND conta_id = :conta",
                conta(conta).addValue("id", id), Long.class);
        if (total == null || total == 0) {
            throw new IllegalArgumentException("Cadastro não encontrado");
        }
    }

    private static MapSqlParameterSource alvo(String conta, long agendamentoId, Agenda.Alteracao alteracao) {
        return conta(conta).addValue("id", agendamentoId).addValue("paciente", alteracao.pacienteId());
    }

    private static String origem(Agenda.Origem origem) {
        return origem == Agenda.Origem.BOT ? "Pelo bot" : "Pela recepção";
    }

    private static String tabela(Agenda.Cadastro cadastro) {
        return switch (cadastro) {
            case PROFISSIONAL -> "agenda.profissional";
            case ESPECIALIDADE -> "agenda.especialidade";
            case CONVENIO -> "agenda.convenio";
        };
    }

    private static String coluna(Agenda.Cadastro cadastro) {
        return switch (cadastro) {
            case PROFISSIONAL -> "profissional_id";
            case ESPECIALIDADE -> "especialidade_id";
            case CONVENIO -> "convenio_id";
        };
    }

    private static MapSqlParameterSource conta(String conta) {
        return new MapSqlParameterSource("conta", conta);
    }

    private static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }

    private Agenda.Grade grade(ResultSet rs, int linha) throws SQLException {
        return new Agenda.Grade(
                rs.getLong("id"),
                rs.getLong("profissional_id"),
                rs.getLong("especialidade_id"),
                rs.getObject("dia_semana", Integer.class),
                rs.getObject("data", LocalDate.class),
                rs.getObject("vigencia_inicio", LocalDate.class),
                rs.getObject("vigencia_fim", LocalDate.class),
                rs.getTime("inicio").toLocalTime(),
                rs.getTime("fim").toLocalTime(),
                rs.getInt("duracao_min"),
                rs.getBoolean("oferece_bot"),
                rs.getString("local"),
                rs.getBoolean("ativo"));
    }

    private Agenda.Bloqueio bloqueio(ResultSet rs, int linha) throws SQLException {
        long profissional = rs.getLong("profissional_id");
        return new Agenda.Bloqueio(
                rs.getLong("id"),
                rs.wasNull() ? null : profissional,
                dataHora(rs, "inicio"),
                dataHora(rs, "fim"),
                rs.getString("motivo"),
                rs.getString("criado_por"));
    }

    private Agenda.Paciente paciente(ResultSet rs, int linha) throws SQLException {
        return new Agenda.Paciente(
                rs.getLong("id"),
                rs.getString("cpf"),
                rs.getDate("nascimento").toLocalDate(),
                rs.getString("nome"),
                rs.getString("telefone"));
    }

    private Agenda.Agendamento agendamento(ResultSet rs, int linha) throws SQLException {
        long convenio = rs.getLong("convenio_id");
        boolean semConvenio = rs.wasNull();
        long remarcado = rs.getLong("remarcado_de");
        boolean semRemarcacao = rs.wasNull();
        String pendencias = rs.getString("pendencias");
        String conferencia = rs.getString("conferencia");
        Date nascimento = rs.getDate("nascimento");
        return new Agenda.Agendamento(
                rs.getLong("id"),
                rs.getLong("profissional_id"),
                rs.getString("profissional"),
                rs.getLong("especialidade_id"),
                rs.getString("especialidade"),
                semConvenio ? null : convenio,
                rs.getString("convenio"),
                new Agenda.Paciente(rs.getLong("paciente_id"), rs.getString("cpf"),
                        nascimento == null ? null : nascimento.toLocalDate(), rs.getString("paciente"), rs.getString("telefone")),
                dataHora(rs, "inicio"),
                dataHora(rs, "fim"),
                rs.getString("local"),
                Agenda.Status.valueOf(rs.getString("status")),
                Agenda.Origem.valueOf(rs.getString("origem")),
                rs.getString("codigo_externo"),
                rs.getString("conversa_id"),
                semRemarcacao ? null : remarcado,
                pendencias == null ? List.of()
                        : Arrays.stream(pendencias.split(",")).map(Agenda.TipoPendencia::valueOf).toList(),
                conferencia == null ? null : Agenda.SituacaoConferencia.valueOf(conferencia));
    }

    private Agenda.Pendencia pendencia(ResultSet rs, int linha) throws SQLException {
        return new Agenda.Pendencia(
            rs.getLong("id"),
            rs.getLong("agendamento_id"),
            Agenda.TipoPendencia.valueOf(rs.getString("tipo")),
            Agenda.SituacaoPendencia.valueOf(rs.getString("situacao")),
            rs.getString("resumo"),
            dataHora(rs, "prazo"),
            instante(rs, "criada_em"),
            instante(rs, "concluida_em"),
            rs.getString("concluida_por"),
            rs.getString("codigo_externo"),
            rs.getString("observacao"),
            rs.getString("conversa_id"),
            dataHora(rs, "inicio"),
            rs.getString("paciente"));
    }

    private static LocalDateTime dataHora(ResultSet rs, String coluna) throws SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toLocalDateTime();
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toInstant();
    }
}
