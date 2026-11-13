package br.com.mandalore.agendamentoconversa.adapter.out.atendimento;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;

/**
 * Persistência JDBC do fluxo clínico (schema {@code atendimento}). Eventos nunca recebem conteúdo clínico.
 */
@Component
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class PostgresAtendimentoAdapter implements AtendimentoPort {

    static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    /** Status do agendamento que ainda aceita chegada. */
    private static final String ATIVO = "status IN ('MARCADO', 'CONFIRMADO')";

    private static final String FICHA = """
            SELECT at.nr_atendimento, at.agendamento_id, at.paciente_id, pa.nome AS paciente, at.profissional_id,
                   p.nome AS profissional, at.especialidade_id, e.nome AS especialidade, at.convenio_id,
                   at.ie_status_atendimento, at.dt_entrada, at.dt_atend_medico, at.dt_fim_consulta, at.dt_alta,
                   at.ie_motivo_alta, at.nm_usuario_entrada, at.nm_usuario_atend, at.nm_usuario_alta,
                   at.dt_cancelamento, at.nm_usuario_cancelamento, at.ds_motivo_cancelamento
            FROM atendimento.atendimento_paciente at
            JOIN agenda.paciente pa ON pa.id = at.paciente_id
            JOIN agenda.profissional p ON p.id = at.profissional_id
            JOIN agenda.especialidade e ON e.id = at.especialidade_id
            """;

    private static final String ANAMNESE = """
            SELECT id, nr_atendimento, paciente_id, profissional_id, ds_queixa_principal,
                   ds_historia_doenca_atual, ds_antecedentes_pessoais, ds_antecedentes_familiares,
                   ds_medicamentos_uso, ds_alergias, ds_habitos, ds_exame_fisico, ds_avaliacao, ds_conduta,
                   dt_registro, dt_liberacao, ie_situacao, dt_inativacao, nm_usuario_inativacao, ds_justificativa,
                   nm_usuario
            FROM atendimento.anamnese_paciente
            """;

    private static final String EVOLUCAO = """
            SELECT cd_evolucao, nr_atendimento, paciente_id, profissional_id, ds_evolucao, qt_peso,
                   qt_altura_cm, dt_retorno_prev, dt_evolucao, dt_liberacao, ie_situacao, dt_inativacao,
                   nm_usuario_inativacao, ds_justificativa, nm_usuario
            FROM atendimento.evolucao_paciente
            """;

    private static final String DIAGNOSTICO = """
            SELECT id, nr_atendimento, paciente_id, profissional_id, cd_doenca, ds_diagnostico, ie_tipo_diagnostico,
                   dt_diagnostico, ie_situacao, dt_inativacao, nm_usuario_inativacao, ds_justificativa, nm_usuario
            FROM atendimento.diagnostico_doenca
            """;

    private static final String RECEITA = """
            SELECT nr_sequencia, nr_atendimento, paciente_id, profissional_id, ie_tipo_receita, ds_observacao,
                   dt_receita, dt_liberacao, dt_validade, ie_situacao, dt_inativacao, nm_usuario_inativacao,
                   ds_justificativa, nm_usuario
            FROM atendimento.med_receita
            """;

    static final String CLINICA_PADRAO = "Clínica";
    private static final int IP_MAX = 64;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final Clock relogio;
    private final Function<String, String> nomeClinica;

    /** Nome impresso na receita: mandalore.clientes.&lt;conta&gt;.nome, senão admin.nome. */
    @Autowired
    public PostgresAtendimentoAdapter(
            @Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Qualifier("observabilityDataSource") DataSource dataSource,
            Environment ambiente
    ) {
        this(jdbc, dataSource, Clock.system(ZONA), conta -> ambiente.getProperty("mandalore.clientes." + conta + ".nome",
                ambiente.getProperty("admin.nome", CLINICA_PADRAO)));
    }

    PostgresAtendimentoAdapter(NamedParameterJdbcTemplate jdbc, DataSource dataSource, Clock relogio) {
        this(jdbc, dataSource, relogio, conta -> CLINICA_PADRAO);
    }

    PostgresAtendimentoAdapter(NamedParameterJdbcTemplate jdbc, DataSource dataSource, Clock relogio,
                               Function<String, String> nomeClinica) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.relogio = relogio;
        this.nomeClinica = nomeClinica;
    }

    // ---- tarefa 5.1 ----

    /** Falha interna para desfazer a transação quando outra recepção ganhou a corrida (uk_atendimento_agendamento). */
    private static final class ChegadaDuplicada extends RuntimeException {
        ChegadaDuplicada(Throwable causa) {
            super(null, causa, false, false);
        }
    }

    @Override
    public Atendimento.Operacao registrarChegada(String conta, long agendamentoId, String ator) {
        try {
            return transacao.execute(status -> chegada(status, conta, agendamentoId, ator));
        } catch (ChegadaDuplicada duplicada) {
            // O INSERT abortou a transação; relê o vencedor fora dela.
            return existente(conta, agendamentoId).map(Atendimento.Operacao::sucesso)
                    .orElseThrow(() -> duplicada);
        }
    }

    private Atendimento.Operacao chegada(org.springframework.transaction.TransactionStatus status, String conta,
                                         long agendamentoId, String ator) {
        Optional<AgendamentoTravado> travado = travarAgendamento(conta, agendamentoId);
        if (travado.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        }
        Optional<Long> existente = existente(conta, agendamentoId);
        if (existente.isPresent()) {
            return Atendimento.Operacao.sucesso(existente.get());
        }
        AgendamentoTravado agendamento = travado.get();
        if (!"MARCADO".equals(agendamento.status()) && !"CONFIRMADO".equals(agendamento.status())) {
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CHEGADA);
        }
        LocalDateTime agora = LocalDateTime.now(relogio);
        if (!agendamento.inicio().toLocalDate().equals(agora.toLocalDate())) {
            return Atendimento.Operacao.falha(CodigoNegocio.CHEGADA_FORA_DO_DIA);
        }
        long nrAtendimento;
        try {
            Long gerado = jdbc.queryForObject("""
                    INSERT INTO atendimento.atendimento_paciente
                        (conta_id, agendamento_id, paciente_id, profissional_id, especialidade_id, convenio_id,
                         ie_status_atendimento, dt_entrada, nm_usuario_entrada)
                    VALUES (:conta, :agendamento, :paciente, :profissional, :especialidade, :convenio,
                            'AGUARDANDO', :entrada, :usuario)
                    RETURNING nr_atendimento
                    """, conta(conta).addValue("agendamento", agendamentoId)
                    .addValue("paciente", agendamento.pacienteId()).addValue("profissional", agendamento.profissionalId())
                    .addValue("especialidade", agendamento.especialidadeId()).addValue("convenio", agendamento.convenioId())
                    .addValue("entrada", Timestamp.valueOf(agora)).addValue("usuario", usuario(ator)), Long.class);
            nrAtendimento = gerado == null ? 0 : gerado;
        } catch (DuplicateKeyException ex) {
            throw new ChegadaDuplicada(ex);
        }
        int alterados = jdbc.update("UPDATE agenda.agendamento SET status = 'AGUARDANDO', atualizado_em = CURRENT_TIMESTAMP "
                + "WHERE id = :id AND conta_id = :conta AND " + ATIVO, conta(conta).addValue("id", agendamentoId));
        if (alterados != 1) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CHEGADA);
        }
        eventoAtendimento(nrAtendimento, "CHEGADA", ator, "");
        eventoAgendamento(agendamentoId, "CHEGOU", ator, "Atendimento " + nrAtendimento);
        return Atendimento.Operacao.sucesso(nrAtendimento);
    }

    private record AgendamentoTravado(String status, LocalDateTime inicio, LocalDateTime fim, long pacienteId,
                                      long profissionalId, long especialidadeId, Long convenioId) {
    }

    private Optional<AgendamentoTravado> travarAgendamento(String conta, long agendamentoId) {
        return jdbc.query("""
                SELECT id, status, inicio, fim, paciente_id, profissional_id, especialidade_id, convenio_id
                FROM agenda.agendamento WHERE id = :id AND conta_id = :conta FOR UPDATE
                """, conta(conta).addValue("id", agendamentoId), (rs, i) -> new AgendamentoTravado(
                rs.getString("status"), rs.getTimestamp("inicio").toLocalDateTime(),
                rs.getTimestamp("fim").toLocalDateTime(), rs.getLong("paciente_id"), rs.getLong("profissional_id"),
                rs.getLong("especialidade_id"), longOuNulo(rs, "convenio_id"))).stream().findFirst();
    }

    // ---- helpers reutilizáveis ----

    /** Atendimento não cancelado do agendamento (no máximo um, por uk_atendimento_agendamento). */
    private Optional<Long> existente(String conta, long agendamentoId) {
        return jdbc.queryForList("""
                SELECT nr_atendimento FROM atendimento.atendimento_paciente
                WHERE agendamento_id = :agendamento AND conta_id = :conta AND ie_status_atendimento <> 'CANCELADO'
                """, conta(conta).addValue("agendamento", agendamentoId), Long.class).stream().findFirst();
    }

    /** Busca por conta; devolve a ficha ou vazio se o atendimento não for da conta. */
    Optional<Atendimento.Ficha> fichaDaConta(String conta, long nrAtendimento) {
        return jdbc.query(FICHA + " WHERE at.nr_atendimento = :nr AND at.conta_id = :conta",
                conta(conta).addValue("nr", nrAtendimento), (rs, i) -> ficha(rs)).stream().findFirst();
    }

    static Atendimento.Ficha ficha(ResultSet rs) throws SQLException {
        String motivo = rs.getString("ie_motivo_alta");
        return new Atendimento.Ficha(rs.getLong("nr_atendimento"), rs.getLong("agendamento_id"), rs.getLong("paciente_id"),
                rs.getString("paciente"), rs.getLong("profissional_id"), rs.getString("profissional"),
                rs.getLong("especialidade_id"), rs.getString("especialidade"), longOuNulo(rs, "convenio_id"),
                Atendimento.Status.valueOf(rs.getString("ie_status_atendimento")), data(rs, "dt_entrada"),
                data(rs, "dt_atend_medico"), data(rs, "dt_fim_consulta"), data(rs, "dt_alta"),
                motivo == null ? null : Atendimento.MotivoAlta.valueOf(motivo), rs.getString("nm_usuario_entrada"),
                rs.getString("nm_usuario_atend"), rs.getString("nm_usuario_alta"), data(rs, "dt_cancelamento"),
                rs.getString("nm_usuario_cancelamento"), rs.getString("ds_motivo_cancelamento"));
    }

    static Atendimento.Anamnese anamnese(ResultSet rs) throws SQLException {
        return new Atendimento.Anamnese(rs.getLong("id"), rs.getLong("nr_atendimento"), rs.getLong("paciente_id"),
                rs.getLong("profissional_id"), rs.getString("ds_queixa_principal"),
                rs.getString("ds_historia_doenca_atual"), rs.getString("ds_antecedentes_pessoais"),
                rs.getString("ds_antecedentes_familiares"), rs.getString("ds_medicamentos_uso"),
                rs.getString("ds_alergias"), rs.getString("ds_habitos"), rs.getString("ds_exame_fisico"),
                rs.getString("ds_avaliacao"), rs.getString("ds_conduta"), data(rs, "dt_registro"), data(rs, "dt_liberacao"), "A".equals(rs.getString("ie_situacao")),
                data(rs, "dt_inativacao"), rs.getString("nm_usuario_inativacao"), rs.getString("ds_justificativa"),
                rs.getString("nm_usuario"));
    }

    static Atendimento.Evolucao evolucao(ResultSet rs) throws SQLException {
        java.sql.Date retorno = rs.getDate("dt_retorno_prev");
        return new Atendimento.Evolucao(rs.getLong("cd_evolucao"), rs.getLong("nr_atendimento"),
                rs.getLong("paciente_id"), rs.getLong("profissional_id"), rs.getString("ds_evolucao"),
                rs.getBigDecimal("qt_peso"), rs.getBigDecimal("qt_altura_cm"),
                retorno == null ? null : retorno.toLocalDate(), data(rs, "dt_evolucao"), data(rs, "dt_liberacao"),
                "A".equals(rs.getString("ie_situacao")), data(rs, "dt_inativacao"),
                rs.getString("nm_usuario_inativacao"), rs.getString("ds_justificativa"), rs.getString("nm_usuario"));
    }

    /** atendimento.atendimento_evento. O detalhe é curto e sem conteúdo clínico. */
    void eventoAtendimento(long nrAtendimento, String tipo, String usuario, String detalhe) {
        jdbc.update("INSERT INTO atendimento.atendimento_evento (nr_atendimento, tipo, usuario, detalhe) "
                        + "VALUES (:nr, :tipo, :usuario, :detalhe)",
                new MapSqlParameterSource("nr", nrAtendimento).addValue("tipo", tipo).addValue("usuario", usuario(usuario))
                        .addValue("detalhe", curto(detalhe)));
    }

    /** agenda.agendamento_evento, mesmo formato do PostgresAgendaAdapter. */
    void eventoAgendamento(long agendamentoId, String tipo, String usuario, String detalhe) {
        jdbc.update("INSERT INTO agenda.agendamento_evento (agendamento_id, tipo, usuario, detalhe) "
                        + "VALUES (:id, :tipo, :usuario, :detalhe)",
                new MapSqlParameterSource("id", agendamentoId).addValue("tipo", tipo).addValue("usuario", usuario(usuario))
                        .addValue("detalhe", curto(detalhe)));
    }

    private static String usuario(String usuario) {
        return usuario == null || usuario.isBlank() ? "sistema" : usuario;
    }

    private static String curto(String detalhe) {
        return detalhe == null ? "" : detalhe.length() > 300 ? detalhe.substring(0, 300) : detalhe;
    }

    private static MapSqlParameterSource conta(String conta) {
        return new MapSqlParameterSource("conta", conta);
    }

    private static Long longOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static LocalDateTime data(ResultSet rs, String coluna) throws SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toLocalDateTime();
    }

    // ---- tarefa 5.2 ----

    /** Fila da recepção: sem conteúdo clínico, só identificação, horário, status e espera. */
    @Override
    public List<Atendimento.FilaItem> fila(String conta, LocalDate data) {
        LocalDateTime agora = LocalDateTime.now(relogio);
        return jdbc.query("""
                SELECT a.id AS agendamento_id, a.inicio, a.status, pa.nome AS paciente, pa.cpf, p.nome AS profissional,
                       e.nome AS especialidade, at.nr_atendimento, at.dt_entrada, at.dt_chamada
                FROM agenda.agendamento a
                JOIN agenda.paciente pa ON pa.id = a.paciente_id
                JOIN agenda.profissional p ON p.id = a.profissional_id
                JOIN agenda.especialidade e ON e.id = a.especialidade_id
                LEFT JOIN atendimento.atendimento_paciente at ON at.agendamento_id = a.id
                     AND at.conta_id = a.conta_id AND at.ie_status_atendimento <> 'CANCELADO'
                WHERE a.conta_id = :conta
                  AND a.status IN ('MARCADO', 'CONFIRMADO', 'AGUARDANDO', 'EM_CONSULTA', 'EXECUTADO', 'FALTOU')
                  AND a.inicio >= :de AND a.inicio < :ate
                ORDER BY a.inicio, pa.nome
                """, conta(conta).addValue("de", Timestamp.valueOf(data.atStartOfDay()))
                        .addValue("ate", Timestamp.valueOf(data.plusDays(1).atStartOfDay())),
                (rs, i) -> filaItem(rs, agora));
    }

    /** Mapeia uma linha da fila (colunas da fila da recepção); espera só para status AGUARDANDO. */
    private static Atendimento.FilaItem filaItem(ResultSet rs, LocalDateTime agora) throws SQLException {
        String status = rs.getString("status");
        LocalDateTime chegada = data(rs, "dt_entrada");
        Long espera = null;
        if ("AGUARDANDO".equals(status) && chegada != null) {
            espera = Math.max(0, java.time.Duration.between(chegada, agora).toMinutes());
        }
        return new Atendimento.FilaItem(rs.getLong("agendamento_id"), longOuNulo(rs, "nr_atendimento"),
                rs.getTimestamp("inicio").toLocalDateTime(), rs.getString("paciente"), rs.getString("cpf"),
                rs.getString("profissional"), rs.getString("especialidade"), status, chegada, espera,
                data(rs, "dt_chamada"));
    }

    // ---- tarefa 6.1 ----

    /** Fila do médico (status = status do atendimento): só atendimentos não cancelados do profissional no dia do agendamento, sem conteúdo clínico. */
    @Override
    public List<Atendimento.FilaItem> filaDoProfissional(String conta, long profissionalId, LocalDate data) {
        LocalDateTime agora = LocalDateTime.now(relogio);
        return jdbc.query("""
                SELECT a.id AS agendamento_id, a.inicio, at.ie_status_atendimento AS status, pa.nome AS paciente,
                       pa.cpf, p.nome AS profissional, e.nome AS especialidade, at.nr_atendimento, at.dt_entrada,
                       at.dt_chamada
                FROM agenda.agendamento a
                JOIN agenda.paciente pa ON pa.id = a.paciente_id
                JOIN agenda.profissional p ON p.id = a.profissional_id
                JOIN agenda.especialidade e ON e.id = a.especialidade_id
                JOIN atendimento.atendimento_paciente at ON at.agendamento_id = a.id
                     AND at.conta_id = a.conta_id AND at.ie_status_atendimento IN ('AGUARDANDO', 'EM_CONSULTA', 'FINALIZADO')
                WHERE a.conta_id = :conta AND a.profissional_id = :profissional
                  AND a.inicio >= :de AND a.inicio < :ate
                ORDER BY CASE at.ie_status_atendimento WHEN 'AGUARDANDO' THEN 0 WHEN 'EM_CONSULTA' THEN 1 ELSE 2 END,
                         at.dt_entrada, at.nr_atendimento
                """, conta(conta).addValue("profissional", profissionalId)
                        .addValue("de", Timestamp.valueOf(data.atStartOfDay()))
                        .addValue("ate", Timestamp.valueOf(data.plusDays(1).atStartOfDay())),
                (rs, i) -> filaItem(rs, agora));
    }

    // ---- tarefa 5.3 ----

    /**
     * Desfaz a chegada (design 9.5). Trava o atendimento da conta (FOR UPDATE) e exige AGUARDANDO.
     * Códigos: inexistente ou de outra conta -> NAO_ENCONTRADO; EM_CONSULTA/FINALIZADO -> ATENDIMENTO_EM_ANDAMENTO;
     * CANCELADO (chegada já desfeita) -> STATUS_INVALIDO_PARA_CHEGADA.
     * O agendamento volta a CONFIRMADO se houver evento CONFIRMADO dele em agenda.agendamento_evento, senão MARCADO.
     * Como o agendamento só chega a AGUARDANDO a partir de MARCADO/CONFIRMADO, e cancelar ou remarcar cria outro
     * status/linha (o antigo deixa de ser AGUARDANDO), qualquer evento CONFIRMADO do próprio agendamento ainda vale.
     * Depois de cancelado o atendimento sai do índice parcial uk_atendimento_agendamento, liberando nova chegada.
     */
    @Override
    public Atendimento.Operacao desfazerChegada(String conta, long nrAtendimento, String motivo, String ator) {
        return transacao.execute(status -> desfazer(status, conta, nrAtendimento, motivo, ator));
    }

    private Atendimento.Operacao desfazer(org.springframework.transaction.TransactionStatus status, String conta,
                                          long nrAtendimento, String motivo, String ator) {
        List<AtendimentoTravado> travados = jdbc.query("""
                SELECT agendamento_id, ie_status_atendimento FROM atendimento.atendimento_paciente
                WHERE nr_atendimento = :nr AND conta_id = :conta FOR UPDATE
                """, conta(conta).addValue("nr", nrAtendimento),
                (rs, i) -> new AtendimentoTravado(rs.getLong("agendamento_id"), rs.getString("ie_status_atendimento")));
        if (travados.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        AtendimentoTravado atendimento = travados.get(0);
        switch (atendimento.status()) {
            case "AGUARDANDO" -> { }
            case "EM_CONSULTA", "FINALIZADO" -> {
                return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_EM_ANDAMENTO);
            }
            default -> {
                return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CHEGADA);
            }
        }
        LocalDateTime agora = LocalDateTime.now(relogio);
        int cancelados = jdbc.update("""
                UPDATE atendimento.atendimento_paciente
                SET ie_status_atendimento = 'CANCELADO', dt_cancelamento = :agora, nm_usuario_cancelamento = :usuario,
                    ds_motivo_cancelamento = :motivo, atualizado_em = CURRENT_TIMESTAMP
                WHERE nr_atendimento = :nr AND conta_id = :conta AND ie_status_atendimento = 'AGUARDANDO'
                """, conta(conta).addValue("nr", nrAtendimento).addValue("agora", Timestamp.valueOf(agora))
                .addValue("usuario", usuario(ator)).addValue("motivo", motivo));
        if (cancelados != 1) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CHEGADA);
        }
        Boolean confirmado = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM agenda.agendamento_evento WHERE agendamento_id = :id AND tipo = 'CONFIRMADO')",
                new MapSqlParameterSource("id", atendimento.agendamentoId()), Boolean.class);
        String anterior = Boolean.TRUE.equals(confirmado) ? "CONFIRMADO" : "MARCADO";
        int alterados = jdbc.update("UPDATE agenda.agendamento SET status = :anterior, atualizado_em = CURRENT_TIMESTAMP "
                        + "WHERE id = :id AND conta_id = :conta AND status = 'AGUARDANDO'",
                conta(conta).addValue("id", atendimento.agendamentoId()).addValue("anterior", anterior));
        if (alterados != 1) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CHEGADA);
        }
        // O motivo é texto livre da recepção (não clínico); o helper limita o tamanho.
        eventoAtendimento(nrAtendimento, "CHEGADA_DESFEITA", ator, motivo);
        eventoAgendamento(atendimento.agendamentoId(), "CHEGADA_DESFEITA", ator, "Atendimento " + nrAtendimento);
        return Atendimento.Operacao.sucesso(nrAtendimento);
    }

    private record AtendimentoTravado(long agendamentoId, String status) {
    }

    /** Histórico do atendimento da conta, em ordem; só tipo, usuário, detalhe curto e horário. */
    @Override
    public List<Atendimento.Evento> eventos(String conta, long nrAtendimento) {
        return jdbc.query("""
                SELECT ev.tipo, ev.usuario, ev.detalhe, ev.criado_em
                FROM atendimento.atendimento_evento ev
                JOIN atendimento.atendimento_paciente at ON at.nr_atendimento = ev.nr_atendimento
                WHERE ev.nr_atendimento = :nr AND at.conta_id = :conta
                ORDER BY ev.criado_em, ev.id
                """, conta(conta).addValue("nr", nrAtendimento), (rs, i) -> new Atendimento.Evento(rs.getString("tipo"),
                rs.getString("usuario"), rs.getString("detalhe"), rs.getTimestamp("criado_em").toInstant()));
    }

    @Override
    public Optional<ProfissionalClinico> profissionalDoUsuario(String conta, long usuarioId) {
        return jdbc.query("""
                SELECT id, nome, conselho_numero, conselho_uf FROM agenda.profissional
                WHERE usuario_id = :usuario AND conta_id = :conta AND ativo
                """, conta(conta).addValue("usuario", usuarioId), (rs, i) -> new ProfissionalClinico(rs.getLong("id"),
                rs.getString("nome"), rs.getString("conselho_numero"), rs.getString("conselho_uf"))).stream().findFirst();
    }

    @Override
    public Optional<Atendimento.Ficha> atendimento(String conta, long nrAtendimento) {
        return fichaDaConta(conta, nrAtendimento);
    }

    @Override
    public Optional<Atendimento.Prontuario> prontuario(String conta, long nrAtendimento) {
        return fichaDaConta(conta, nrAtendimento)
                .map(ficha -> prontuario(conta, ficha, cabecalhoPaciente(conta, ficha).orElse(null)));
    }

    private Atendimento.Prontuario prontuario(String conta, Atendimento.Ficha ficha,
                                              Atendimento.CabecalhoPaciente paciente) {
        long nrAtendimento = ficha.nrAtendimento();
        return new Atendimento.Prontuario(ficha, anamneseAtiva(conta, nrAtendimento).orElse(null),
                evolucoesAtivas(conta, nrAtendimento), diagnosticosAtivos(conta, nrAtendimento),
                receitasAtivas(conta, nrAtendimento), alta(conta, nrAtendimento).orElse(null), paciente);
    }

    private Optional<Atendimento.CabecalhoPaciente> cabecalhoPaciente(String conta, Atendimento.Ficha ficha) {
        String anteriores = jdbc.queryForList("""
                SELECT ds_alergias FROM atendimento.anamnese_paciente
                WHERE conta_id = :conta AND paciente_id = :paciente AND ie_situacao = 'A' AND dt_liberacao IS NOT NULL
                  AND btrim(ds_alergias) <> ''
                ORDER BY dt_liberacao DESC, id DESC LIMIT 1
                """, conta(conta).addValue("paciente", ficha.pacienteId()), String.class).stream().findFirst().orElse("");
        List<Atendimento.Alergia> alergias = alergiasAtivas(conta, ficha.pacienteId());
        return jdbc.query("""
                SELECT pa.id, pa.nome, pa.nascimento, c.nome AS convenio
                FROM agenda.paciente pa
                LEFT JOIN agenda.convenio c ON c.id = :convenio
                WHERE pa.id = :paciente AND pa.conta_id = :conta
                """, conta(conta).addValue("paciente", ficha.pacienteId()).addValue("convenio", ficha.convenioId()),
                (rs, i) -> {
                    java.sql.Date nascimento = rs.getDate("nascimento");
                    return new Atendimento.CabecalhoPaciente(rs.getLong("id"), rs.getString("nome"),
                            nascimento == null ? null : nascimento.toLocalDate(), rs.getString("convenio"), alergias,
                            anteriores);
                }).stream().findFirst();
    }

    private List<Atendimento.Alergia> alergiasAtivas(String conta, long pacienteId) {
        return jdbc.query("""
                SELECT id, paciente_id, ds_alergia, dt_registro, nm_usuario FROM atendimento.alergia_paciente
                WHERE conta_id = :conta AND paciente_id = :paciente AND ie_situacao = 'A'
                ORDER BY dt_registro, id
                """, conta(conta).addValue("paciente", pacienteId), (rs, i) -> new Atendimento.Alergia(rs.getLong("id"),
                rs.getLong("paciente_id"), rs.getString("ds_alergia"), data(rs, "dt_registro"), rs.getString("nm_usuario")));
    }

    private Optional<Atendimento.Anamnese> anamneseAtiva(String conta, long nrAtendimento) {
        return jdbc.query(ANAMNESE + " WHERE nr_atendimento = :nr AND conta_id = :conta AND ie_situacao = 'A'",
                conta(conta).addValue("nr", nrAtendimento), (rs, i) -> anamnese(rs)).stream().findFirst();
    }

    private List<Atendimento.Evolucao> evolucoesAtivas(String conta, long nrAtendimento) {
        return jdbc.query(EVOLUCAO + " WHERE nr_atendimento = :nr AND conta_id = :conta AND ie_situacao = 'A' "
                        + "ORDER BY dt_evolucao, cd_evolucao",
                conta(conta).addValue("nr", nrAtendimento), (rs, i) -> evolucao(rs));
    }

    /** Atendimentos não cancelados do paciente na conta, do mais recente ao mais antigo. */
    @Override
    public List<Atendimento.Prontuario> historicoDoPaciente(String conta, long pacienteId) {
        return jdbc.query(FICHA + """
                         WHERE at.paciente_id = :paciente AND at.conta_id = :conta
                           AND at.ie_status_atendimento <> 'CANCELADO'
                         ORDER BY at.dt_entrada DESC, at.nr_atendimento DESC
                        """, conta(conta).addValue("paciente", pacienteId), (rs, i) -> ficha(rs)).stream()
                .map(ficha -> prontuario(conta, ficha, null))
                .toList();
    }

    private Optional<Atendimento.Alta> alta(String conta, long nrAtendimento) {
        return jdbc.query("""
                SELECT nr_atendimento, ie_desfecho, ds_orientacao, dt_retorno_prev, dt_liberacao, nm_usuario
                FROM atendimento.atendimento_alta WHERE nr_atendimento = :nr AND conta_id = :conta
                """, conta(conta).addValue("nr", nrAtendimento), (rs, i) -> {
            java.sql.Date retorno = rs.getDate("dt_retorno_prev");
            return new Atendimento.Alta(rs.getLong("nr_atendimento"), Atendimento.Desfecho.valueOf(rs.getString("ie_desfecho")),
                    rs.getString("ds_orientacao"), retorno == null ? null : retorno.toLocalDate(),
                    data(rs, "dt_liberacao"), rs.getString("nm_usuario"));
        }).stream().findFirst();
    }

    @Override
    public Optional<Atendimento.Anamnese> anamnese(String conta, long anamneseId) {
        return jdbc.query(ANAMNESE + " WHERE id = :id AND conta_id = :conta",
                conta(conta).addValue("id", anamneseId), (rs, i) -> anamnese(rs)).stream().findFirst();
    }

    @Override
    public Optional<Atendimento.Evolucao> evolucao(String conta, long evolucaoId) {
        return jdbc.query(EVOLUCAO + " WHERE cd_evolucao = :id AND conta_id = :conta",
                conta(conta).addValue("id", evolucaoId), (rs, i) -> evolucao(rs)).stream().findFirst();
    }

    @Override
    public Optional<Atendimento.Diagnostico> diagnostico(String conta, long diagnosticoId) {
        return jdbc.query(DIAGNOSTICO + " WHERE id = :id AND conta_id = :conta",
                conta(conta).addValue("id", diagnosticoId), (rs, i) -> diagnostico(rs)).stream().findFirst();
    }

    @Override
    public Optional<Atendimento.Receita> receita(String conta, long receitaId) {
        return jdbc.query(RECEITA + " WHERE nr_sequencia = :id AND conta_id = :conta",
                conta(conta).addValue("id", receitaId), (rs, i) -> receitaSemItens(rs)).stream().findFirst()
                .map(this::comItens);
    }

    @Override
    public Optional<CabecalhoReceita> cabecalhoReceita(String conta, long receitaId) {
        return jdbc.query("""
                SELECT pa.nome, pa.cpf FROM atendimento.med_receita r
                JOIN agenda.paciente pa ON pa.id = r.paciente_id
                WHERE r.nr_sequencia = :id AND r.conta_id = :conta
                """, conta(conta).addValue("id", receitaId), (rs, i) -> new CabecalhoReceita(nomeClinica.apply(conta),
                rs.getString("nome"), rs.getString("cpf"))).stream().findFirst();
    }

    @Override
    public Optional<CabecalhoReceita> cabecalhoAtendimento(String conta, long nrAtendimento) {
        return jdbc.query("""
                SELECT pa.nome, pa.cpf FROM atendimento.atendimento_paciente a
                JOIN agenda.paciente pa ON pa.id = a.paciente_id
                WHERE a.nr_atendimento = :nr AND a.conta_id = :conta
                """, conta(conta).addValue("nr", nrAtendimento), (rs, i) -> new CabecalhoReceita(nomeClinica.apply(conta),
                rs.getString("nome"), rs.getString("cpf"))).stream().findFirst();
    }

    /** Anamnese/evolução liberadas e ativas; rascunhos ativos de anamnese, evolução e receita. */
    @Override
    public ContagemClinica contagemParaFinalizar(String conta, long nrAtendimento) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM atendimento.anamnese_paciente WHERE nr_atendimento = :nr AND conta_id = :conta
                     AND ie_situacao = 'A' AND dt_liberacao IS NOT NULL)
                  + (SELECT count(*) FROM atendimento.evolucao_paciente WHERE nr_atendimento = :nr AND conta_id = :conta
                     AND ie_situacao = 'A' AND dt_liberacao IS NOT NULL) AS liberadas,
                  (SELECT count(*) FROM atendimento.anamnese_paciente WHERE nr_atendimento = :nr AND conta_id = :conta
                     AND ie_situacao = 'A' AND dt_liberacao IS NULL)
                  + (SELECT count(*) FROM atendimento.evolucao_paciente WHERE nr_atendimento = :nr AND conta_id = :conta
                     AND ie_situacao = 'A' AND dt_liberacao IS NULL)
                  + (SELECT count(*) FROM atendimento.med_receita WHERE nr_atendimento = :nr AND conta_id = :conta
                     AND ie_situacao = 'A' AND dt_liberacao IS NULL) AS rascunhos
                """, conta(conta).addValue("nr", nrAtendimento),
                (rs, i) -> new ContagemClinica(rs.getInt("liberadas"), rs.getInt("rascunhos")));
    }

    /** PEP_ACESSO. Grava só quem, o quê e de onde; nunca o conteúdo lido. */
    @Override
    public void registrarAcesso(String conta, Long nrAtendimento, long pacienteId, String acao, String usuario, String ip) {
        jdbc.update("""
                INSERT INTO atendimento.acesso_prontuario (conta_id, nr_atendimento, paciente_id, acao, usuario, ip)
                VALUES (:conta, :nr, :paciente, :acao, :usuario, :ip)
                """, conta(conta).addValue("nr", nrAtendimento).addValue("paciente", pacienteId).addValue("acao", acao)
                .addValue("usuario", usuario(usuario))
                .addValue("ip", ip == null ? null : ip.length() > IP_MAX ? ip.substring(0, IP_MAX) : ip));
    }

    // ---- tarefa 6.2 ----

    /** Falha interna que desfaz a transação (abortada pelo Postgres) quando o profissional já está em consulta. */
    private static final class ProfissionalOcupado extends RuntimeException {
        ProfissionalOcupado(Throwable causa) {
            super(null, causa, false, false);
        }
    }

    /**
     * Inicia a consulta (design 9.2). A posse é validada aqui também: atendimento de outro profissional é NAO_ENCONTRADO.
     * A violação de uk_atendimento_profissional_em_consulta vira PROFISSIONAL_EM_CONSULTA; outra violação é relançada.
     */
    @Override
    public Atendimento.Operacao iniciarConsulta(String conta, long nrAtendimento, long profissionalId, String ator) {
        try {
            return transacao.execute(status -> iniciar(status, conta, nrAtendimento, profissionalId, ator));
        } catch (ProfissionalOcupado ocupado) {
            return Atendimento.Operacao.falha(CodigoNegocio.PROFISSIONAL_EM_CONSULTA);
        }
    }

    private Atendimento.Operacao iniciar(org.springframework.transaction.TransactionStatus status, String conta,
                                         long nrAtendimento, long profissionalId, String ator) {
        List<AtendimentoDoProfissional> travados = jdbc.query("""
                SELECT agendamento_id, profissional_id, ie_status_atendimento FROM atendimento.atendimento_paciente
                WHERE nr_atendimento = :nr AND conta_id = :conta FOR UPDATE
                """, conta(conta).addValue("nr", nrAtendimento), (rs, i) -> new AtendimentoDoProfissional(
                rs.getLong("agendamento_id"), rs.getLong("profissional_id"), rs.getString("ie_status_atendimento")));
        if (travados.isEmpty() || travados.get(0).profissionalId() != profissionalId) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        AtendimentoDoProfissional atendimento = travados.get(0);
        if (!"AGUARDANDO".equals(atendimento.status())) {
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CONSULTA);
        }
        LocalDateTime agora = LocalDateTime.now(relogio);
        int iniciados;
        try {
            iniciados = jdbc.update("""
                    UPDATE atendimento.atendimento_paciente
                    SET ie_status_atendimento = 'EM_CONSULTA', dt_atend_medico = :agora, nm_usuario_atend = :usuario,
                        atualizado_em = CURRENT_TIMESTAMP
                    WHERE nr_atendimento = :nr AND conta_id = :conta AND profissional_id = :profissional
                      AND ie_status_atendimento = 'AGUARDANDO'
                    """, conta(conta).addValue("nr", nrAtendimento).addValue("profissional", profissionalId)
                    .addValue("agora", Timestamp.valueOf(agora)).addValue("usuario", usuario(ator)));
        } catch (DuplicateKeyException ex) {
            String mensagem = ex.getMessage();
            if (mensagem != null && mensagem.contains("uk_atendimento_profissional_em_consulta")) {
                throw new ProfissionalOcupado(ex);
            }
            throw ex;
        }
        if (iniciados != 1) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CONSULTA);
        }
        int alterados = jdbc.update("UPDATE agenda.agendamento SET status = 'EM_CONSULTA', atualizado_em = CURRENT_TIMESTAMP "
                        + "WHERE id = :id AND conta_id = :conta AND status = 'AGUARDANDO'",
                conta(conta).addValue("id", atendimento.agendamentoId()));
        if (alterados != 1) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CONSULTA);
        }
        eventoAtendimento(nrAtendimento, "CONSULTA_INICIADA", ator, "");
        eventoAgendamento(atendimento.agendamentoId(), "EM_CONSULTA", ator, "Atendimento " + nrAtendimento);
        return Atendimento.Operacao.sucesso(nrAtendimento);
    }

    private record AtendimentoDoProfissional(long agendamentoId, long profissionalId, String status) {
    }

    // ---- tarefa 7.1 ----

    @Override
    public Atendimento.Operacao salvarRascunhoAnamnese(String conta, long nrAtendimento, long profissionalId,
                                                       Atendimento.Anamnese anamnese, String ator) {
        return transacao.execute(status -> {
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            LocalDateTime agora = LocalDateTime.now(relogio);
            List<Long> ids = jdbc.query("""
                    INSERT INTO atendimento.anamnese_paciente
                        (conta_id, nr_atendimento, paciente_id, profissional_id, ds_queixa_principal,
                         ds_historia_doenca_atual, ds_antecedentes_pessoais, ds_antecedentes_familiares,
                         ds_medicamentos_uso, ds_alergias, ds_habitos, ds_exame_fisico, ds_avaliacao, ds_conduta,
                         dt_registro, nm_usuario)
                    VALUES (:conta, :nr, :paciente, :profissional, :queixa, :historia, :pessoais, :familiares,
                            :medicamentos, :alergias, :habitos, :exame, :avaliacao, :conduta, :agora, :usuario)
                    ON CONFLICT (nr_atendimento) WHERE ie_situacao = 'A' DO UPDATE
                    SET ds_queixa_principal = EXCLUDED.ds_queixa_principal,
                        ds_historia_doenca_atual = EXCLUDED.ds_historia_doenca_atual,
                        ds_antecedentes_pessoais = EXCLUDED.ds_antecedentes_pessoais,
                        ds_antecedentes_familiares = EXCLUDED.ds_antecedentes_familiares,
                        ds_medicamentos_uso = EXCLUDED.ds_medicamentos_uso,
                        ds_alergias = EXCLUDED.ds_alergias,
                        ds_habitos = EXCLUDED.ds_habitos,
                        ds_exame_fisico = EXCLUDED.ds_exame_fisico,
                        ds_avaliacao = EXCLUDED.ds_avaliacao,
                        ds_conduta = EXCLUDED.ds_conduta,
                        nm_usuario = EXCLUDED.nm_usuario,
                        atualizado_em = CURRENT_TIMESTAMP
                    WHERE anamnese_paciente.dt_liberacao IS NULL
                    RETURNING id
                    """, parametrosAnamnese(conta, nrAtendimento, atendimento.orElseThrow().pacienteId(), profissionalId,
                            anamnese, ator, agora), (rs, i) -> rs.getLong("id"));
            if (ids.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            long id = ids.get(0);
            eventoAtendimento(nrAtendimento, "ANAMNESE_SALVA", ator, "Anamnese " + id);
            return Atendimento.Operacao.sucesso(id);
        });
    }

    @Override
    public Atendimento.Operacao liberarAnamnese(String conta, long nrAtendimento, long profissionalId, String ator) {
        return transacao.execute(status -> {
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            List<AnamneseTravada> anamneses = jdbc.query("""
                    SELECT id, dt_liberacao, ds_queixa_principal, ie_situacao
                    FROM atendimento.anamnese_paciente
                    WHERE nr_atendimento = :nr AND conta_id = :conta AND profissional_id = :profissional
                      AND ie_situacao = 'A'
                    FOR UPDATE
                    """, conta(conta).addValue("nr", nrAtendimento).addValue("profissional", profissionalId),
                    (rs, i) -> new AnamneseTravada(rs.getLong("id"), data(rs, "dt_liberacao"),
                            rs.getString("ds_queixa_principal"), true));
            if (anamneses.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            AnamneseTravada anamnese = anamneses.get(0);
            if (anamnese.dtLiberacao() != null) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            if (anamnese.queixaPrincipal() == null || anamnese.queixaPrincipal().isBlank()) {
                throw new IllegalArgumentException("Informe a queixa principal para liberar a anamnese");
            }
            int liberadas = jdbc.update("""
                    UPDATE atendimento.anamnese_paciente
                    SET dt_liberacao = :agora, atualizado_em = CURRENT_TIMESTAMP
                    WHERE id = :id AND conta_id = :conta AND nr_atendimento = :nr
                      AND profissional_id = :profissional AND ie_situacao = 'A' AND dt_liberacao IS NULL
                    """, conta(conta).addValue("id", anamnese.id()).addValue("nr", nrAtendimento)
                    .addValue("profissional", profissionalId)
                    .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio))));
            if (liberadas != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            eventoAtendimento(nrAtendimento, "ANAMNESE_LIBERADA", ator, "Anamnese " + anamnese.id());
            return Atendimento.Operacao.sucesso(anamnese.id());
        });
    }

    @Override
    public Atendimento.Operacao inativarAnamnese(String conta, long anamneseId, long profissionalId, String justificativa,
                                                 String ator) {
        String motivo = justificativa(justificativa);
        return transacao.execute(status -> {
            List<Long> atendimentos = jdbc.queryForList("""
                    SELECT nr_atendimento FROM atendimento.anamnese_paciente
                    WHERE id = :id AND conta_id = :conta AND profissional_id = :profissional
                    """, conta(conta).addValue("id", anamneseId).addValue("profissional", profissionalId), Long.class);
            if (atendimentos.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            long nrAtendimento = atendimentos.get(0);
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            List<AnamneseTravada> anamneses = jdbc.query("""
                    SELECT id, dt_liberacao, ds_queixa_principal, ie_situacao
                    FROM atendimento.anamnese_paciente
                    WHERE id = :id AND conta_id = :conta AND nr_atendimento = :nr AND profissional_id = :profissional
                    FOR UPDATE
                    """, conta(conta).addValue("id", anamneseId).addValue("nr", nrAtendimento)
                    .addValue("profissional", profissionalId), (rs, i) -> new AnamneseTravada(rs.getLong("id"),
                            data(rs, "dt_liberacao"), rs.getString("ds_queixa_principal"),
                            "A".equals(rs.getString("ie_situacao"))));
            if (anamneses.isEmpty() || !anamneses.get(0).ativa()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            if (anamneses.get(0).dtLiberacao() == null) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_NAO_LIBERADO);
            }
            int inativadas = jdbc.update("""
                    UPDATE atendimento.anamnese_paciente
                    SET ie_situacao = 'I', dt_inativacao = :agora, nm_usuario_inativacao = :usuario,
                        ds_justificativa = :justificativa, atualizado_em = CURRENT_TIMESTAMP
                    WHERE id = :id AND conta_id = :conta AND nr_atendimento = :nr
                      AND profissional_id = :profissional AND ie_situacao = 'A' AND dt_liberacao IS NOT NULL
                    """, conta(conta).addValue("id", anamneseId).addValue("nr", nrAtendimento)
                    .addValue("profissional", profissionalId).addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio)))
                    .addValue("usuario", usuario(ator)).addValue("justificativa", motivo));
            if (inativadas != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            eventoAtendimento(nrAtendimento, "ANAMNESE_INATIVADA", ator, "Anamnese " + anamneseId);
            return Atendimento.Operacao.sucesso(anamneseId);
        });
    }

    private Optional<AtendimentoClinicoTravado> travarAtendimento(String conta, long nrAtendimento, long profissionalId) {
        return jdbc.query("""
                SELECT paciente_id, ie_status_atendimento FROM atendimento.atendimento_paciente
                WHERE nr_atendimento = :nr AND conta_id = :conta AND profissional_id = :profissional
                FOR UPDATE
                """, conta(conta).addValue("nr", nrAtendimento).addValue("profissional", profissionalId),
                (rs, i) -> new AtendimentoClinicoTravado(rs.getLong("paciente_id"), rs.getString("ie_status_atendimento")))
                .stream().findFirst();
    }

    private static CodigoNegocio bloqueioClinico(Optional<AtendimentoClinicoTravado> atendimento) {
        if (atendimento.isEmpty()) {
            return CodigoNegocio.NAO_ENCONTRADO;
        }
        return "EM_CONSULTA".equals(atendimento.get().status()) ? null : CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA;
    }

    private static MapSqlParameterSource parametrosAnamnese(String conta, long nrAtendimento, long pacienteId,
                                                            long profissionalId, Atendimento.Anamnese anamnese,
                                                            String ator, LocalDateTime agora) {
        return conta(conta).addValue("nr", nrAtendimento).addValue("paciente", pacienteId)
                .addValue("profissional", profissionalId).addValue("queixa", texto(anamnese.queixaPrincipal()))
                .addValue("historia", texto(anamnese.historiaDoencaAtual()))
                .addValue("pessoais", texto(anamnese.antecedentesPessoais()))
                .addValue("familiares", texto(anamnese.antecedentesFamiliares()))
                .addValue("medicamentos", texto(anamnese.medicamentosEmUso()))
                .addValue("alergias", texto(anamnese.alergias())).addValue("habitos", texto(anamnese.habitos()))
                .addValue("exame", texto(anamnese.exameFisico())).addValue("avaliacao", texto(anamnese.avaliacao()))
                .addValue("conduta", texto(anamnese.conduta())).addValue("agora", Timestamp.valueOf(agora))
                .addValue("usuario", usuario(ator));
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor;
    }

    private static String justificativa(String valor) {
        String texto = valor == null ? "" : valor.trim();
        if (texto.isEmpty()) {
            throw new IllegalArgumentException("Informe a justificativa");
        }
        if (texto.length() > 500) {
            throw new IllegalArgumentException("A justificativa deve ter no máximo 500 caracteres");
        }
        return texto;
    }

    private record AtendimentoClinicoTravado(long pacienteId, String status) {
    }

    private record AnamneseTravada(long id, LocalDateTime dtLiberacao, String queixaPrincipal, boolean ativa) {
    }

    // ---- tarefa 8.1 ----

    /** Cria um rascunho; um atendimento pode ter várias evoluções. Paciente vem do atendimento travado. */
    @Override
    public Atendimento.Operacao criarEvolucao(String conta, long nrAtendimento, long profissionalId,
                                              Atendimento.Evolucao evolucao, String ator) {
        return transacao.execute(status -> {
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            Long id = jdbc.queryForObject("""
                    INSERT INTO atendimento.evolucao_paciente
                        (conta_id, nr_atendimento, paciente_id, profissional_id, ds_evolucao, qt_peso, qt_altura_cm,
                         dt_retorno_prev, dt_evolucao, nm_usuario)
                    VALUES (:conta, :nr, :paciente, :profissional, :texto, :peso, :altura, :retorno, :agora, :usuario)
                    RETURNING cd_evolucao
                    """, conta(conta).addValue("nr", nrAtendimento)
                    .addValue("paciente", atendimento.orElseThrow().pacienteId())
                    .addValue("profissional", profissionalId).addValue("texto", texto(evolucao.texto()))
                    .addValue("peso", evolucao.pesoKg()).addValue("altura", evolucao.alturaCm())
                    .addValue("retorno", evolucao.retornoPrevisto() == null ? null : java.sql.Date.valueOf(evolucao.retornoPrevisto()))
                    .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio))).addValue("usuario", usuario(ator)),
                    Long.class);
            long novo = id == null ? 0 : id;
            eventoAtendimento(nrAtendimento, "EVOLUCAO_SALVA", ator, "Evolucao " + novo);
            return Atendimento.Operacao.sucesso(novo);
        });
    }

    /** Altera só rascunho ativo e não liberado. */
    @Override
    public Atendimento.Operacao editarEvolucao(String conta, long evolucaoId, long profissionalId,
                                               Atendimento.Evolucao evolucao, String ator) {
        return transacao.execute(status -> {
            Optional<EvolucaoTravada> travada = travarEvolucao(conta, evolucaoId, profissionalId);
            if (travada.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, travada.get().nrAtendimento(),
                    profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            EvolucaoTravada atual = lerEvolucaoTravada(conta, evolucaoId, profissionalId);
            if (atual == null || !atual.ativa()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            if (atual.dtLiberacao() != null) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            int alteradas = jdbc.update("""
                    UPDATE atendimento.evolucao_paciente
                    SET ds_evolucao = :texto, qt_peso = :peso, qt_altura_cm = :altura, dt_retorno_prev = :retorno,
                        nm_usuario = :usuario, atualizado_em = CURRENT_TIMESTAMP
                    WHERE cd_evolucao = :id AND conta_id = :conta AND nr_atendimento = :nr
                      AND profissional_id = :profissional AND ie_situacao = 'A' AND dt_liberacao IS NULL
                    """, conta(conta).addValue("id", evolucaoId).addValue("nr", atual.nrAtendimento())
                    .addValue("profissional", profissionalId).addValue("texto", texto(evolucao.texto()))
                    .addValue("peso", evolucao.pesoKg()).addValue("altura", evolucao.alturaCm())
                    .addValue("retorno", evolucao.retornoPrevisto() == null ? null : java.sql.Date.valueOf(evolucao.retornoPrevisto()))
                    .addValue("usuario", usuario(ator)));
            if (alteradas != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            eventoAtendimento(atual.nrAtendimento(), "EVOLUCAO_SALVA", ator, "Evolucao " + evolucaoId);
            return Atendimento.Operacao.sucesso(evolucaoId);
        });
    }

    /** Marca dt_liberacao; recusa texto vazio lido sob lock. */
    @Override
    public Atendimento.Operacao liberarEvolucao(String conta, long evolucaoId, long profissionalId, String ator) {
        return transacao.execute(status -> {
            Optional<EvolucaoTravada> travada = travarEvolucao(conta, evolucaoId, profissionalId);
            if (travada.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, travada.get().nrAtendimento(),
                    profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            EvolucaoTravada atual = lerEvolucaoTravada(conta, evolucaoId, profissionalId);
            if (atual == null || !atual.ativa()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            if (atual.dtLiberacao() != null) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            if (atual.texto() == null || atual.texto().isBlank()) {
                throw new IllegalArgumentException("Escreva a evolução antes de liberar");
            }
            int liberadas = jdbc.update("""
                    UPDATE atendimento.evolucao_paciente
                    SET dt_liberacao = :agora, atualizado_em = CURRENT_TIMESTAMP
                    WHERE cd_evolucao = :id AND conta_id = :conta AND nr_atendimento = :nr
                      AND profissional_id = :profissional AND ie_situacao = 'A' AND dt_liberacao IS NULL
                    """, conta(conta).addValue("id", evolucaoId).addValue("nr", atual.nrAtendimento())
                    .addValue("profissional", profissionalId)
                    .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio))));
            if (liberadas != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            eventoAtendimento(atual.nrAtendimento(), "EVOLUCAO_LIBERADA", ator, "Evolucao " + evolucaoId);
            return Atendimento.Operacao.sucesso(evolucaoId);
        });
    }

    /** Inativa só registro ativo e liberado, com justificativa obrigatória. */
    @Override
    public Atendimento.Operacao inativarEvolucao(String conta, long evolucaoId, long profissionalId, String justificativa,
                                                 String ator) {
        String motivo = justificativa(justificativa);
        return transacao.execute(status -> {
            Optional<EvolucaoTravada> travada = travarEvolucao(conta, evolucaoId, profissionalId);
            if (travada.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, travada.get().nrAtendimento(),
                    profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            EvolucaoTravada atual = lerEvolucaoTravada(conta, evolucaoId, profissionalId);
            if (atual == null || !atual.ativa()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            if (atual.dtLiberacao() == null) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_NAO_LIBERADO);
            }
            int inativadas = jdbc.update("""
                    UPDATE atendimento.evolucao_paciente
                    SET ie_situacao = 'I', dt_inativacao = :agora, nm_usuario_inativacao = :usuario,
                        ds_justificativa = :justificativa, atualizado_em = CURRENT_TIMESTAMP
                    WHERE cd_evolucao = :id AND conta_id = :conta AND nr_atendimento = :nr
                      AND profissional_id = :profissional AND ie_situacao = 'A' AND dt_liberacao IS NOT NULL
                    """, conta(conta).addValue("id", evolucaoId).addValue("nr", atual.nrAtendimento())
                    .addValue("profissional", profissionalId).addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio)))
                    .addValue("usuario", usuario(ator)).addValue("justificativa", motivo));
            if (inativadas != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            eventoAtendimento(atual.nrAtendimento(), "EVOLUCAO_INATIVADA", ator, "Evolucao " + evolucaoId);
            return Atendimento.Operacao.sucesso(evolucaoId);
        });
    }

    /** Descobre o atendimento da evolução (sem lock) para travar o atendimento primeiro, na mesma ordem da anamnese. */
    private Optional<EvolucaoTravada> travarEvolucao(String conta, long evolucaoId, long profissionalId) {
        return jdbc.query("""
                SELECT nr_atendimento FROM atendimento.evolucao_paciente
                WHERE cd_evolucao = :id AND conta_id = :conta AND profissional_id = :profissional
                """, conta(conta).addValue("id", evolucaoId).addValue("profissional", profissionalId),
                (rs, i) -> new EvolucaoTravada(rs.getLong("nr_atendimento"), null, null, false)).stream().findFirst();
    }

    /** Relê a evolução com FOR UPDATE, já com o atendimento travado. */
    private EvolucaoTravada lerEvolucaoTravada(String conta, long evolucaoId, long profissionalId) {
        return jdbc.query("""
                SELECT nr_atendimento, dt_liberacao, ds_evolucao, ie_situacao
                FROM atendimento.evolucao_paciente
                WHERE cd_evolucao = :id AND conta_id = :conta AND profissional_id = :profissional
                FOR UPDATE
                """, conta(conta).addValue("id", evolucaoId).addValue("profissional", profissionalId),
                (rs, i) -> new EvolucaoTravada(rs.getLong("nr_atendimento"), data(rs, "dt_liberacao"),
                        rs.getString("ds_evolucao"), "A".equals(rs.getString("ie_situacao")))).stream().findFirst()
                .orElse(null);
    }

    private record EvolucaoTravada(long nrAtendimento, LocalDateTime dtLiberacao, String texto, boolean ativa) {
    }

    // ---- tarefa 9.1 ----

    /** Falha interna que desfaz a transação (abortada pelo Postgres) numa violação de índice de diagnóstico. */
    private static final class DiagnosticoConflito extends RuntimeException {
        private final CodigoNegocio codigo;

        DiagnosticoConflito(CodigoNegocio codigo, Throwable causa) {
            super(null, causa, false, false);
            this.codigo = codigo;
        }
    }

    /**
     * Inclui o diagnóstico (ativo desde a criação; diagnóstico não tem liberação). Paciente vem do atendimento travado.
     * uk_diagnostico_principal -> DIAGNOSTICO_PRINCIPAL_JA_EXISTE; uk_diagnostico_cid -> DIAGNOSTICO_DUPLICADO;
     * CHECK de formato do CID -> IllegalArgumentException; outras violações são relançadas.
     */
    @Override
    public Atendimento.Operacao incluirDiagnostico(String conta, long nrAtendimento, long profissionalId,
                                                   Atendimento.Diagnostico diagnostico, String ator) {
        try {
            return transacao.execute(status -> {
                Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
                CodigoNegocio bloqueio = bloqueioClinico(atendimento);
                if (bloqueio != null) {
                    return Atendimento.Operacao.falha(bloqueio);
                }
                Long id;
                try {
                    id = jdbc.queryForObject("""
                            INSERT INTO atendimento.diagnostico_doenca
                                (conta_id, nr_atendimento, paciente_id, profissional_id, cd_doenca, ds_diagnostico,
                                 ie_tipo_diagnostico, dt_diagnostico, nm_usuario)
                            VALUES (:conta, :nr, :paciente, :profissional, :cid, :descricao, :tipo, :agora, :usuario)
                            RETURNING id
                            """, conta(conta).addValue("nr", nrAtendimento)
                            .addValue("paciente", atendimento.orElseThrow().pacienteId())
                            .addValue("profissional", profissionalId).addValue("cid", diagnostico.cid())
                            .addValue("descricao", texto(diagnostico.descricao()))
                            .addValue("tipo", diagnostico.tipo().name())
                            .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio)))
                            .addValue("usuario", usuario(ator)), Long.class);
                } catch (DuplicateKeyException ex) {
                    String mensagem = ex.getMessage() == null ? "" : ex.getMessage();
                    if (mensagem.contains("uk_diagnostico_principal")) {
                        throw new DiagnosticoConflito(CodigoNegocio.DIAGNOSTICO_PRINCIPAL_JA_EXISTE, ex);
                    }
                    if (mensagem.contains("uk_diagnostico_cid")) {
                        throw new DiagnosticoConflito(CodigoNegocio.DIAGNOSTICO_DUPLICADO, ex);
                    }
                    throw ex;
                } catch (DataIntegrityViolationException ex) {
                    String mensagem = ex.getMessage() == null ? "" : ex.getMessage();
                    if (mensagem.contains("cd_doenca")) {
                        throw new IllegalArgumentException("CID inválido: use o formato A00 ou A00.0", ex);
                    }
                    throw ex;
                }
                long novo = id == null ? 0 : id;
                eventoAtendimento(nrAtendimento, "DIAGNOSTICO_INCLUIDO", ator, "Diagnostico " + novo);
                return Atendimento.Operacao.sucesso(novo);
            });
        } catch (DiagnosticoConflito conflito) {
            return Atendimento.Operacao.falha(conflito.codigo);
        }
    }

    /** Inativa só diagnóstico ativo, com justificativa obrigatória. Liberar o principal libera o índice. */
    @Override
    public Atendimento.Operacao inativarDiagnostico(String conta, long diagnosticoId, long profissionalId,
                                                    String justificativa, String ator) {
        String motivo = justificativa(justificativa);
        return transacao.execute(status -> {
            List<Long> atendimentos = jdbc.queryForList("""
                    SELECT nr_atendimento FROM atendimento.diagnostico_doenca
                    WHERE id = :id AND conta_id = :conta AND profissional_id = :profissional
                    """, conta(conta).addValue("id", diagnosticoId).addValue("profissional", profissionalId), Long.class);
            if (atendimentos.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            long nrAtendimento = atendimentos.get(0);
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            List<String> situacoes = jdbc.queryForList("""
                    SELECT ie_situacao FROM atendimento.diagnostico_doenca
                    WHERE id = :id AND conta_id = :conta AND nr_atendimento = :nr AND profissional_id = :profissional
                    FOR UPDATE
                    """, conta(conta).addValue("id", diagnosticoId).addValue("nr", nrAtendimento)
                    .addValue("profissional", profissionalId), String.class);
            if (situacoes.isEmpty() || !"A".equals(situacoes.get(0))) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            int inativados = jdbc.update("""
                    UPDATE atendimento.diagnostico_doenca
                    SET ie_situacao = 'I', dt_inativacao = :agora, nm_usuario_inativacao = :usuario,
                        ds_justificativa = :justificativa
                    WHERE id = :id AND conta_id = :conta AND nr_atendimento = :nr
                      AND profissional_id = :profissional AND ie_situacao = 'A'
                    """, conta(conta).addValue("id", diagnosticoId).addValue("nr", nrAtendimento)
                    .addValue("profissional", profissionalId).addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio)))
                    .addValue("usuario", usuario(ator)).addValue("justificativa", motivo));
            if (inativados != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            eventoAtendimento(nrAtendimento, "DIAGNOSTICO_INATIVADO", ator, "Diagnostico " + diagnosticoId);
            return Atendimento.Operacao.sucesso(diagnosticoId);
        });
    }

    static Atendimento.Diagnostico diagnostico(ResultSet rs) throws SQLException {
        return new Atendimento.Diagnostico(rs.getLong("id"), rs.getLong("nr_atendimento"), rs.getLong("paciente_id"),
                rs.getLong("profissional_id"), rs.getString("cd_doenca"), rs.getString("ds_diagnostico"),
                Atendimento.TipoDiagnostico.valueOf(rs.getString("ie_tipo_diagnostico")), data(rs, "dt_diagnostico"),
                "A".equals(rs.getString("ie_situacao")), data(rs, "dt_inativacao"),
                rs.getString("nm_usuario_inativacao"), rs.getString("ds_justificativa"), rs.getString("nm_usuario"));
    }

    private List<Atendimento.Diagnostico> diagnosticosAtivos(String conta, long nrAtendimento) {
        return jdbc.query(DIAGNOSTICO + " WHERE nr_atendimento = :nr AND conta_id = :conta AND ie_situacao = 'A' "
                        + "ORDER BY CASE ie_tipo_diagnostico WHEN 'PRINCIPAL' THEN 0 ELSE 1 END, dt_diagnostico, id",
                conta(conta).addValue("nr", nrAtendimento), (rs, i) -> diagnostico(rs));
    }

    // ---- tarefa 10.1 ----

    /** Cria a receita em rascunho com os itens na ordem recebida. Paciente vem do atendimento travado. */
    @Override
    public Atendimento.Operacao criarReceita(String conta, long nrAtendimento, long profissionalId,
                                             Atendimento.Receita receita, String ator) {
        return transacao.execute(status -> {
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            Long id = jdbc.queryForObject("""
                    INSERT INTO atendimento.med_receita
                        (conta_id, nr_atendimento, paciente_id, profissional_id, ie_tipo_receita, ds_observacao,
                         dt_receita, nm_usuario)
                    VALUES (:conta, :nr, :paciente, :profissional, :tipo, :observacao, :agora, :usuario)
                    RETURNING nr_sequencia
                    """, conta(conta).addValue("nr", nrAtendimento)
                    .addValue("paciente", atendimento.orElseThrow().pacienteId())
                    .addValue("profissional", profissionalId).addValue("tipo", tipoReceita(receita).name())
                    .addValue("observacao", texto(receita.observacao()))
                    .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio))).addValue("usuario", usuario(ator)),
                    Long.class);
            long novo = id == null ? 0 : id;
            inserirItens(novo, receita.itens());
            eventoAtendimento(nrAtendimento, "RECEITA_SALVA", ator, "Receita " + novo);
            return Atendimento.Operacao.sucesso(novo);
        });
    }

    /** Só rascunho ativo: troca tipo e observação e reinsere os itens renumerados. */
    @Override
    public Atendimento.Operacao editarReceita(String conta, long receitaId, long profissionalId,
                                              Atendimento.Receita receita, String ator) {
        return transacao.execute(status -> {
            ReceitaTravada atual = travarReceita(conta, receitaId, profissionalId);
            CodigoNegocio bloqueio = bloqueioReceita(atual);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            if (atual.dtLiberacao() != null) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            int alteradas = jdbc.update("""
                    UPDATE atendimento.med_receita
                    SET ie_tipo_receita = :tipo, ds_observacao = :observacao, nm_usuario = :usuario,
                        atualizado_em = CURRENT_TIMESTAMP
                    WHERE nr_sequencia = :id AND conta_id = :conta AND profissional_id = :profissional
                      AND ie_situacao = 'A' AND dt_liberacao IS NULL
                    """, conta(conta).addValue("id", receitaId).addValue("profissional", profissionalId)
                    .addValue("tipo", tipoReceita(receita).name()).addValue("observacao", texto(receita.observacao()))
                    .addValue("usuario", usuario(ator)));
            if (alteradas != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            jdbc.update("DELETE FROM atendimento.med_receita_item WHERE nr_seq_receita = :id",
                    new MapSqlParameterSource("id", receitaId));
            inserirItens(receitaId, receita.itens());
            eventoAtendimento(atual.nrAtendimento(), "RECEITA_SALVA", ator, "Receita " + receitaId);
            return Atendimento.Operacao.sucesso(receitaId);
        });
    }

    /** Marca dt_liberacao e a validade; recusa, sob lock, receita sem item com medicamento e posologia. */
    @Override
    public Atendimento.Operacao liberarReceita(String conta, long receitaId, long profissionalId, LocalDate validade,
                                               String ator) {
        if (validade == null) {
            throw new IllegalArgumentException("Informe a validade da receita");
        }
        return transacao.execute(status -> {
            ReceitaTravada atual = travarReceita(conta, receitaId, profissionalId);
            CodigoNegocio bloqueio = bloqueioReceita(atual);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            if (atual.dtLiberacao() != null) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            Integer completos = jdbc.queryForObject("""
                    SELECT count(*) FROM atendimento.med_receita_item
                    WHERE nr_seq_receita = :id AND btrim(ds_medicamento) <> '' AND btrim(ds_posologia) <> ''
                    """, new MapSqlParameterSource("id", receitaId), Integer.class);
            if (completos == null || completos < 1) {
                throw new IllegalArgumentException("Informe ao menos um medicamento com posologia");
            }
            int liberadas = jdbc.update("""
                    UPDATE atendimento.med_receita
                    SET dt_liberacao = :agora, dt_validade = :validade, atualizado_em = CURRENT_TIMESTAMP
                    WHERE nr_sequencia = :id AND conta_id = :conta AND profissional_id = :profissional
                      AND ie_situacao = 'A' AND dt_liberacao IS NULL
                    """, conta(conta).addValue("id", receitaId).addValue("profissional", profissionalId)
                    .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio)))
                    .addValue("validade", java.sql.Date.valueOf(validade)));
            if (liberadas != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_JA_LIBERADO);
            }
            eventoAtendimento(atual.nrAtendimento(), "RECEITA_LIBERADA", ator, "Receita " + receitaId);
            return Atendimento.Operacao.sucesso(receitaId);
        });
    }

    /** Cancela só receita ativa e liberada, com justificativa; o registro e os itens permanecem. */
    @Override
    public Atendimento.Operacao cancelarReceita(String conta, long receitaId, long profissionalId, String justificativa,
                                                String ator) {
        String motivo = justificativa(justificativa);
        return transacao.execute(status -> {
            ReceitaTravada atual = travarReceita(conta, receitaId, profissionalId);
            CodigoNegocio bloqueio = bloqueioReceita(atual);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            if (atual.dtLiberacao() == null) {
                return Atendimento.Operacao.falha(CodigoNegocio.REGISTRO_NAO_LIBERADO);
            }
            int canceladas = jdbc.update("""
                    UPDATE atendimento.med_receita
                    SET ie_situacao = 'I', dt_inativacao = :agora, nm_usuario_inativacao = :usuario,
                        ds_justificativa = :justificativa, atualizado_em = CURRENT_TIMESTAMP
                    WHERE nr_sequencia = :id AND conta_id = :conta AND profissional_id = :profissional
                      AND ie_situacao = 'A' AND dt_liberacao IS NOT NULL
                    """, conta(conta).addValue("id", receitaId).addValue("profissional", profissionalId)
                    .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio)))
                    .addValue("usuario", usuario(ator)).addValue("justificativa", motivo));
            if (canceladas != 1) {
                status.setRollbackOnly();
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            eventoAtendimento(atual.nrAtendimento(), "RECEITA_CANCELADA", ator, "Receita " + receitaId);
            return Atendimento.Operacao.sucesso(receitaId);
        });
    }

    /**
     * Trava o atendimento e depois a receita (mesma ordem da anamnese e da evolução). Nulo se a receita não for da
     * conta e do profissional.
     */
    private ReceitaTravada travarReceita(String conta, long receitaId, long profissionalId) {
        List<Long> atendimentos = jdbc.queryForList("""
                SELECT nr_atendimento FROM atendimento.med_receita
                WHERE nr_sequencia = :id AND conta_id = :conta AND profissional_id = :profissional
                """, conta(conta).addValue("id", receitaId).addValue("profissional", profissionalId), Long.class);
        if (atendimentos.isEmpty()) {
            return null;
        }
        long nrAtendimento = atendimentos.get(0);
        Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
        if (atendimento.isEmpty()) {
            return null;
        }
        return jdbc.query("""
                SELECT dt_liberacao, ie_situacao FROM atendimento.med_receita
                WHERE nr_sequencia = :id AND conta_id = :conta AND profissional_id = :profissional
                FOR UPDATE
                """, conta(conta).addValue("id", receitaId).addValue("profissional", profissionalId),
                (rs, i) -> new ReceitaTravada(nrAtendimento, atendimento.get().status(), data(rs, "dt_liberacao"),
                        "A".equals(rs.getString("ie_situacao")))).stream().findFirst().orElse(null);
    }

    private static CodigoNegocio bloqueioReceita(ReceitaTravada receita) {
        if (receita == null) {
            return CodigoNegocio.NAO_ENCONTRADO;
        }
        if (!"EM_CONSULTA".equals(receita.statusAtendimento())) {
            return CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA;
        }
        return receita.ativa() ? null : CodigoNegocio.NAO_ENCONTRADO;
    }

    private void inserirItens(long receitaId, List<Atendimento.ReceitaItem> itens) {
        int ordem = 0;
        for (Atendimento.ReceitaItem item : itens) {
            ordem++;
            jdbc.update("""
                    INSERT INTO atendimento.med_receita_item
                        (nr_seq_receita, nr_ordem, ds_medicamento, ds_apresentacao, ds_posologia, ie_via_aplicacao,
                         ds_quantidade, ie_uso_continuo, ds_observacao)
                    VALUES (:receita, :ordem, :medicamento, :apresentacao, :posologia, :via, :quantidade, :continuo,
                            :observacao)
                    """, new MapSqlParameterSource("receita", receitaId).addValue("ordem", ordem)
                    .addValue("medicamento", texto(item.medicamento())).addValue("apresentacao", texto(item.apresentacao()))
                    .addValue("posologia", texto(item.posologia())).addValue("via", texto(item.via()))
                    .addValue("quantidade", texto(item.quantidade())).addValue("continuo", item.usoContinuo())
                    .addValue("observacao", texto(item.observacao())));
        }
    }

    private static Atendimento.TipoReceita tipoReceita(Atendimento.Receita receita) {
        return receita.tipo() == null ? Atendimento.TipoReceita.COMUM : receita.tipo();
    }

    private static Atendimento.Receita receitaSemItens(ResultSet rs) throws SQLException {
        java.sql.Date validade = rs.getDate("dt_validade");
        return new Atendimento.Receita(rs.getLong("nr_sequencia"), rs.getLong("nr_atendimento"),
                rs.getLong("paciente_id"), rs.getLong("profissional_id"),
                Atendimento.TipoReceita.valueOf(rs.getString("ie_tipo_receita")), rs.getString("ds_observacao"),
                data(rs, "dt_receita"), data(rs, "dt_liberacao"), validade == null ? null : validade.toLocalDate(),
                "A".equals(rs.getString("ie_situacao")), data(rs, "dt_inativacao"),
                rs.getString("nm_usuario_inativacao"), rs.getString("ds_justificativa"), rs.getString("nm_usuario"),
                List.of());
    }

    private Atendimento.Receita comItens(Atendimento.Receita r) {
        List<Atendimento.ReceitaItem> itens = jdbc.query("""
                SELECT nr_ordem, ds_medicamento, ds_apresentacao, ds_posologia, ie_via_aplicacao, ds_quantidade,
                       ie_uso_continuo, ds_observacao
                FROM atendimento.med_receita_item WHERE nr_seq_receita = :id ORDER BY nr_ordem
                """, new MapSqlParameterSource("id", r.id()), (rs, i) -> new Atendimento.ReceitaItem(rs.getInt("nr_ordem"),
                rs.getString("ds_medicamento"), rs.getString("ds_apresentacao"), rs.getString("ds_posologia"),
                rs.getString("ie_via_aplicacao"), rs.getString("ds_quantidade"), rs.getBoolean("ie_uso_continuo"),
                rs.getString("ds_observacao")));
        return new Atendimento.Receita(r.id(), r.nrAtendimento(), r.pacienteId(), r.profissionalId(), r.tipo(),
                r.observacao(), r.dtReceita(), r.dtLiberacao(), r.dtValidade(), r.ativa(), r.dtInativacao(),
                r.usuarioInativacao(), r.justificativa(), r.usuario(), itens);
    }

    private List<Atendimento.Receita> receitasAtivas(String conta, long nrAtendimento) {
        return jdbc.query(RECEITA + " WHERE nr_atendimento = :nr AND conta_id = :conta AND ie_situacao = 'A' "
                        + "ORDER BY dt_receita, nr_sequencia",
                conta(conta).addValue("nr", nrAtendimento), (rs, i) -> receitaSemItens(rs)).stream()
                .map(this::comItens)
                .toList();
    }

    private record ReceitaTravada(long nrAtendimento, String statusAtendimento, LocalDateTime dtLiberacao,
                                  boolean ativa) {
    }

    // ---- tarefa 11.1 ----

    /**
     * Finaliza (design 9.4): com o atendimento travado, reconta registros liberados e rascunhos, grava a alta, põe o
     * atendimento em FINALIZADO e o agendamento em EXECUTADO, que continua ocupando o horário.
     */
    @Override
    public Atendimento.Operacao finalizar(String conta, long nrAtendimento, long profissionalId,
                                          Atendimento.MotivoAlta motivoAlta, Atendimento.Alta alta, String ator) {
        if (motivoAlta == null || alta == null) {
            throw new IllegalArgumentException("Informe o motivo da alta");
        }
        return transacao.execute(status -> encerrar(status, conta, nrAtendimento, profissionalId, motivoAlta, alta,
                false, null, ator));
    }

    @Override
    public Atendimento.Operacao finalizarEAssinar(String conta, long nrAtendimento, long profissionalId,
                                                  Atendimento.MotivoAlta motivoAlta, Atendimento.Alta alta,
                                                  LocalDate validadeReceita, String ator) {
        if (motivoAlta == null || alta == null) {
            throw new IllegalArgumentException("Informe o motivo da alta");
        }
        return transacao.execute(status -> encerrar(status, conta, nrAtendimento, profissionalId, motivoAlta, alta,
                true, validadeReceita, ator));
    }

    /** Falha depois de liberar rascunhos desfaz a transação inteira: nada fica liberado sem a alta. */
    private Atendimento.Operacao encerrar(org.springframework.transaction.TransactionStatus status, String conta,
                                          long nrAtendimento, long profissionalId, Atendimento.MotivoAlta motivoAlta,
                                          Atendimento.Alta alta, boolean liberarRascunhos, LocalDate validadeReceita,
                                          String ator) {
        List<AtendimentoDoProfissional> travados = jdbc.query("""
                SELECT agendamento_id, profissional_id, ie_status_atendimento FROM atendimento.atendimento_paciente
                WHERE nr_atendimento = :nr AND conta_id = :conta FOR UPDATE
                """, conta(conta).addValue("nr", nrAtendimento), (rs, i) -> new AtendimentoDoProfissional(
                rs.getLong("agendamento_id"), rs.getLong("profissional_id"), rs.getString("ie_status_atendimento")));
        if (travados.isEmpty() || travados.get(0).profissionalId() != profissionalId) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        AtendimentoDoProfissional atendimento = travados.get(0);
        if (!"EM_CONSULTA".equals(atendimento.status())) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        if (liberarRascunhos) {
            liberarRascunhos(conta, nrAtendimento, profissionalId, validadeReceita, ator);
        }
        ContagemClinica contagem = contagemParaFinalizar(conta, nrAtendimento);
        if (contagem.rascunhos() > 0) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.RASCUNHO_PENDENTE);
        }
        if (contagem.anamneseEvolucaoLiberadas() < 1) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.SEM_REGISTRO_CLINICO);
        }
        LocalDateTime agora = LocalDateTime.now(relogio);
        jdbc.update("""
                INSERT INTO atendimento.atendimento_alta
                    (nr_atendimento, conta_id, ie_desfecho, ds_orientacao, dt_retorno_prev, dt_liberacao, nm_usuario)
                VALUES (:nr, :conta, :desfecho, :orientacao, :retorno, :agora, :usuario)
                """, conta(conta).addValue("nr", nrAtendimento)
                .addValue("desfecho", (alta.desfecho() == null ? Atendimento.Desfecho.ALTA : alta.desfecho()).name())
                .addValue("orientacao", texto(alta.orientacao()))
                .addValue("retorno", alta.retornoPrevisto() == null ? null : java.sql.Date.valueOf(alta.retornoPrevisto()))
                .addValue("agora", Timestamp.valueOf(agora)).addValue("usuario", usuario(ator)));
        int finalizados = jdbc.update("""
                UPDATE atendimento.atendimento_paciente
                SET ie_status_atendimento = 'FINALIZADO', dt_fim_consulta = :agora, dt_alta = :agora,
                    ie_motivo_alta = :motivo, nm_usuario_alta = :usuario, atualizado_em = CURRENT_TIMESTAMP
                WHERE nr_atendimento = :nr AND conta_id = :conta AND profissional_id = :profissional
                  AND ie_status_atendimento = 'EM_CONSULTA'
                """, conta(conta).addValue("nr", nrAtendimento).addValue("profissional", profissionalId)
                .addValue("agora", Timestamp.valueOf(agora)).addValue("motivo", motivoAlta.name())
                .addValue("usuario", usuario(ator)));
        if (finalizados != 1) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        int alterados = jdbc.update("UPDATE agenda.agendamento SET status = 'EXECUTADO', atualizado_em = CURRENT_TIMESTAMP "
                        + "WHERE id = :id AND conta_id = :conta AND status = 'EM_CONSULTA'",
                conta(conta).addValue("id", atendimento.agendamentoId()));
        if (alterados != 1) {
            status.setRollbackOnly();
            return Atendimento.Operacao.falha(CodigoNegocio.ATENDIMENTO_NAO_EM_CONSULTA);
        }
        eventoAtendimento(nrAtendimento, "FINALIZADO", ator, motivoAlta.name());
        eventoAgendamento(atendimento.agendamentoId(), "EXECUTADO", ator, "Atendimento " + nrAtendimento);
        return Atendimento.Operacao.sucesso(nrAtendimento);
    }

    static final String JUSTIFICATIVA_DESCARTE = "Rascunho vazio descartado na finalização";

    /**
     * Com o atendimento já travado: libera os rascunhos com conteúdo e inativa os vazios (anamnese, evoluções e
     * receitas do profissional). Rascunho com conteúdo que não fecha a regra de liberação lança IllegalArgumentException.
     */
    private void liberarRascunhos(String conta, long nrAtendimento, long profissionalId, LocalDate validadeReceita,
                                  String ator) {
        MapSqlParameterSource base = conta(conta).addValue("nr", nrAtendimento).addValue("profissional", profissionalId)
                .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio))).addValue("usuario", usuario(ator))
                .addValue("justificativa", JUSTIFICATIVA_DESCARTE);
        String rascunho = " WHERE nr_atendimento = :nr AND conta_id = :conta AND profissional_id = :profissional"
                + " AND ie_situacao = 'A' AND dt_liberacao IS NULL";

        List<RascunhoAnamnese> anamneses = jdbc.query("""
                SELECT id, ds_queixa_principal,
                       concat(ds_queixa_principal, ds_historia_doenca_atual, ds_antecedentes_pessoais,
                              ds_antecedentes_familiares, ds_medicamentos_uso, ds_alergias, ds_habitos, ds_exame_fisico,
                              ds_avaliacao, ds_conduta) AS conteudo
                FROM atendimento.anamnese_paciente""" + rascunho + " FOR UPDATE", base,
                (rs, i) -> new RascunhoAnamnese(rs.getLong("id"), rs.getString("ds_queixa_principal"),
                        rs.getString("conteudo")));
        for (RascunhoAnamnese a : anamneses) {
            MapSqlParameterSource p = new MapSqlParameterSource(base.getValues()).addValue("id", a.id());
            if (vazio(a.conteudo())) {
                jdbc.update("UPDATE atendimento.anamnese_paciente SET ie_situacao = 'I', dt_inativacao = :agora, "
                        + "nm_usuario_inativacao = :usuario, ds_justificativa = :justificativa, "
                        + "atualizado_em = CURRENT_TIMESTAMP" + rascunho + " AND id = :id", p);
                eventoAtendimento(nrAtendimento, "ANAMNESE_DESCARTADA", ator, "Anamnese " + a.id());
                continue;
            }
            if (vazio(a.queixaPrincipal())) {
                throw new IllegalArgumentException("Informe a queixa principal para finalizar");
            }
            jdbc.update("UPDATE atendimento.anamnese_paciente SET dt_liberacao = :agora, atualizado_em = CURRENT_TIMESTAMP"
                    + rascunho + " AND id = :id", p);
            eventoAtendimento(nrAtendimento, "ANAMNESE_LIBERADA", ator, "Anamnese " + a.id());
        }

        List<RascunhoEvolucao> evolucoes = jdbc.query("""
                SELECT cd_evolucao, ds_evolucao,
                       (qt_peso IS NOT NULL OR qt_altura_cm IS NOT NULL OR dt_retorno_prev IS NOT NULL) AS tem_dados
                FROM atendimento.evolucao_paciente""" + rascunho + " FOR UPDATE", base,
                (rs, i) -> new RascunhoEvolucao(rs.getLong("cd_evolucao"), rs.getString("ds_evolucao"),
                        rs.getBoolean("tem_dados")));
        for (RascunhoEvolucao e : evolucoes) {
            MapSqlParameterSource p = new MapSqlParameterSource(base.getValues()).addValue("id", e.id());
            if (vazio(e.texto()) && e.temDados()) {
                throw new IllegalArgumentException("Escreva o texto da evolução ou apague o rascunho antes de finalizar");
            }
            if (vazio(e.texto())) {
                jdbc.update("UPDATE atendimento.evolucao_paciente SET ie_situacao = 'I', dt_inativacao = :agora, "
                        + "nm_usuario_inativacao = :usuario, ds_justificativa = :justificativa, "
                        + "atualizado_em = CURRENT_TIMESTAMP" + rascunho + " AND cd_evolucao = :id", p);
                eventoAtendimento(nrAtendimento, "EVOLUCAO_DESCARTADA", ator, "Evolucao " + e.id());
                continue;
            }
            jdbc.update("UPDATE atendimento.evolucao_paciente SET dt_liberacao = :agora, atualizado_em = CURRENT_TIMESTAMP"
                    + rascunho + " AND cd_evolucao = :id", p);
            eventoAtendimento(nrAtendimento, "EVOLUCAO_LIBERADA", ator, "Evolucao " + e.id());
        }

        List<RascunhoReceita> receitas = jdbc.query("""
                SELECT r.nr_sequencia,
                       (SELECT count(*) FROM atendimento.med_receita_item i WHERE i.nr_seq_receita = r.nr_sequencia
                          AND btrim(i.ds_medicamento) <> '' AND btrim(i.ds_posologia) <> '') AS completos,
                       (btrim(r.ds_observacao) <> '' OR EXISTS (SELECT 1 FROM atendimento.med_receita_item i
                          WHERE i.nr_seq_receita = r.nr_sequencia
                            AND concat(btrim(i.ds_medicamento), btrim(i.ds_apresentacao), btrim(i.ds_posologia),
                                       btrim(i.ie_via_aplicacao), btrim(i.ds_quantidade), btrim(i.ds_observacao)) <> '')
                       ) AS tem_conteudo
                FROM atendimento.med_receita r
                WHERE r.nr_atendimento = :nr AND r.conta_id = :conta AND r.profissional_id = :profissional
                  AND r.ie_situacao = 'A' AND r.dt_liberacao IS NULL
                FOR UPDATE OF r
                """, base,
                (rs, i) -> new RascunhoReceita(rs.getLong("nr_sequencia"), rs.getInt("completos"),
                        rs.getBoolean("tem_conteudo")));
        for (RascunhoReceita r : receitas) {
            MapSqlParameterSource p = new MapSqlParameterSource(base.getValues()).addValue("id", r.id());
            if (r.completos() > 0) {
                if (validadeReceita == null) {
                    throw new IllegalArgumentException("Informe a validade da receita");
                }
                jdbc.update("UPDATE atendimento.med_receita SET dt_liberacao = :agora, dt_validade = :validade, "
                        + "atualizado_em = CURRENT_TIMESTAMP" + rascunho + " AND nr_sequencia = :id",
                        p.addValue("validade", java.sql.Date.valueOf(validadeReceita)));
                eventoAtendimento(nrAtendimento, "RECEITA_LIBERADA", ator, "Receita " + r.id());
                continue;
            }
            if (r.temConteudo()) {
                throw new IllegalArgumentException("Complete a receita: informe medicamento e posologia em ao menos um item");
            }
            jdbc.update("UPDATE atendimento.med_receita SET ie_situacao = 'I', dt_inativacao = :agora, "
                    + "nm_usuario_inativacao = :usuario, ds_justificativa = :justificativa, "
                    + "atualizado_em = CURRENT_TIMESTAMP" + rascunho + " AND nr_sequencia = :id", p);
            eventoAtendimento(nrAtendimento, "RECEITA_DESCARTADA", ator, "Receita " + r.id());
        }
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }

    private record RascunhoAnamnese(long id, String queixaPrincipal, String conteudo) {
    }

    private record RascunhoEvolucao(long id, String texto, boolean temDados) {
    }

    private record RascunhoReceita(long id, int completos, boolean temConteudo) {
    }

    // ---- alergias do paciente ----

    /** Falha interna que desfaz a transação (abortada pelo Postgres) quando a alergia já está ativa. */
    private static final class AlergiaDuplicada extends RuntimeException {
        AlergiaDuplicada(Throwable causa) {
            super(null, causa, false, false);
        }
    }

    @Override
    public Atendimento.Operacao incluirAlergia(String conta, long nrAtendimento, long profissionalId, String descricao,
                                               String ator) {
        try {
            return transacao.execute(status -> {
                Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
                CodigoNegocio bloqueio = bloqueioClinico(atendimento);
                if (bloqueio != null) {
                    return Atendimento.Operacao.falha(bloqueio);
                }
                Long id;
                try {
                    id = jdbc.queryForObject("""
                            INSERT INTO atendimento.alergia_paciente
                                (conta_id, paciente_id, nr_atendimento, ds_alergia, dt_registro, nm_usuario)
                            VALUES (:conta, :paciente, :nr, :descricao, :agora, :usuario)
                            RETURNING id
                            """, conta(conta).addValue("paciente", atendimento.orElseThrow().pacienteId())
                            .addValue("nr", nrAtendimento).addValue("descricao", descricao)
                            .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio)))
                            .addValue("usuario", usuario(ator)), Long.class);
                } catch (DuplicateKeyException ex) {
                    String mensagem = ex.getMessage() == null ? "" : ex.getMessage();
                    if (mensagem.contains("uk_alergia_ativa")) {
                        throw new AlergiaDuplicada(ex);
                    }
                    throw ex;
                }
                long novo = id == null ? 0 : id;
                eventoAtendimento(nrAtendimento, "ALERGIA_INCLUIDA", ator, "Alergia " + novo);
                return Atendimento.Operacao.sucesso(novo);
            });
        } catch (AlergiaDuplicada duplicada) {
            return Atendimento.Operacao.falha(CodigoNegocio.ALERGIA_DUPLICADA);
        }
    }

    @Override
    public Atendimento.Operacao inativarAlergia(String conta, long nrAtendimento, long alergiaId, long profissionalId,
                                                String justificativa, String ator) {
        String motivo = justificativa(justificativa);
        return transacao.execute(status -> {
            Optional<AtendimentoClinicoTravado> atendimento = travarAtendimento(conta, nrAtendimento, profissionalId);
            CodigoNegocio bloqueio = bloqueioClinico(atendimento);
            if (bloqueio != null) {
                return Atendimento.Operacao.falha(bloqueio);
            }
            int inativadas = jdbc.update("""
                    UPDATE atendimento.alergia_paciente
                    SET ie_situacao = 'I', dt_inativacao = :agora, nm_usuario_inativacao = :usuario,
                        ds_justificativa = :justificativa
                    WHERE id = :id AND conta_id = :conta AND paciente_id = :paciente AND ie_situacao = 'A'
                    """, conta(conta).addValue("id", alergiaId).addValue("paciente", atendimento.orElseThrow().pacienteId())
                    .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio))).addValue("usuario", usuario(ator))
                    .addValue("justificativa", motivo));
            if (inativadas != 1) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            eventoAtendimento(nrAtendimento, "ALERGIA_INATIVADA", ator, "Alergia " + alergiaId);
            return Atendimento.Operacao.sucesso(alergiaId);
        });
    }

    // ---- recepção: chamada, falta e encaixe ----

    /** Status do agendamento que toma o horário do profissional (mesma lista de uk_agendamento_ocupa_horario). */
    private static final String OCUPA_HORARIO = "status IN ('MARCADO', 'CONFIRMADO', 'AGUARDANDO', 'EM_CONSULTA', 'EXECUTADO')";

    @Override
    public Atendimento.Operacao chamar(String conta, long nrAtendimento, Long profissionalId, String ator) {
        return transacao.execute(status -> {
            List<String> situacao = jdbc.queryForList("""
                    SELECT ie_status_atendimento FROM atendimento.atendimento_paciente
                    WHERE nr_atendimento = :nr AND conta_id = :conta
                      AND (CAST(:profissional AS BIGINT) IS NULL OR profissional_id = CAST(:profissional AS BIGINT))
                    FOR UPDATE
                    """, conta(conta).addValue("nr", nrAtendimento).addValue("profissional", profissionalId), String.class);
            if (situacao.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            if (!"AGUARDANDO".equals(situacao.get(0))) {
                return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_CHAMADA);
            }
            jdbc.update("""
                    UPDATE atendimento.atendimento_paciente
                    SET dt_chamada = :agora, nm_usuario_chamada = :usuario, atualizado_em = CURRENT_TIMESTAMP
                    WHERE nr_atendimento = :nr AND conta_id = :conta
                    """, conta(conta).addValue("nr", nrAtendimento)
                    .addValue("agora", Timestamp.valueOf(LocalDateTime.now(relogio))).addValue("usuario", usuario(ator)));
            eventoAtendimento(nrAtendimento, "CHAMADO", ator, "");
            return Atendimento.Operacao.sucesso(nrAtendimento);
        });
    }

    @Override
    public Atendimento.Operacao marcarFalta(String conta, long agendamentoId, String ator) {
        return transacao.execute(status -> {
            Optional<AgendamentoTravado> travado = travarAgendamento(conta, agendamentoId);
            if (travado.isEmpty()) {
                return Atendimento.Operacao.falha(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
            }
            String atual = travado.get().status();
            if (!"MARCADO".equals(atual) && !"CONFIRMADO".equals(atual)) {
                return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_FALTA);
            }
            if (travado.get().inicio().isAfter(LocalDateTime.now(relogio))) {
                return Atendimento.Operacao.falha(CodigoNegocio.FALTA_ANTES_DO_HORARIO);
            }
            jdbc.update("UPDATE agenda.agendamento SET status = 'FALTOU', atualizado_em = CURRENT_TIMESTAMP "
                    + "WHERE id = :id AND conta_id = :conta AND " + ATIVO, conta(conta).addValue("id", agendamentoId));
            eventoAgendamento(agendamentoId, "FALTOU", ator, "");
            return Atendimento.Operacao.sucesso(agendamentoId);
        });
    }

    /** Volta ao status de antes da falta pelo mesmo critério do desfazer chegada (evento CONFIRMADO). */
    @Override
    public Atendimento.Operacao desfazerFalta(String conta, long agendamentoId, String ator) {
        try {
            return transacao.execute(status -> {
                Optional<AgendamentoTravado> travado = travarAgendamento(conta, agendamentoId);
                if (travado.isEmpty()) {
                    return Atendimento.Operacao.falha(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
                }
                AgendamentoTravado agendamento = travado.get();
                if (!"FALTOU".equals(agendamento.status())) {
                    return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_FALTA);
                }
                Boolean ocupado = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM agenda.agendamento
                            WHERE conta_id = :conta AND profissional_id = :profissional AND id <> :id
                              AND inicio < :fim AND fim > :inicio AND\s""" + OCUPA_HORARIO + ")",
                        conta(conta).addValue("profissional", agendamento.profissionalId()).addValue("id", agendamentoId)
                                .addValue("inicio", Timestamp.valueOf(agendamento.inicio()))
                                .addValue("fim", Timestamp.valueOf(agendamento.fim())), Boolean.class);
                if (Boolean.TRUE.equals(ocupado)) {
                    return Atendimento.Operacao.falha(CodigoNegocio.HORARIO_INDISPONIVEL);
                }
                Boolean confirmado = jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM agenda.agendamento_evento WHERE agendamento_id = :id AND tipo = 'CONFIRMADO')",
                        new MapSqlParameterSource("id", agendamentoId), Boolean.class);
                jdbc.update("UPDATE agenda.agendamento SET status = :anterior, atualizado_em = CURRENT_TIMESTAMP "
                                + "WHERE id = :id AND conta_id = :conta AND status = 'FALTOU'",
                        conta(conta).addValue("id", agendamentoId)
                                .addValue("anterior", Boolean.TRUE.equals(confirmado) ? "CONFIRMADO" : "MARCADO"));
                eventoAgendamento(agendamentoId, "FALTA_DESFEITA", ator, "");
                return Atendimento.Operacao.sucesso(agendamentoId);
            });
        } catch (DuplicateKeyException ex) {
            return Atendimento.Operacao.falha(CodigoNegocio.HORARIO_INDISPONIVEL);
        }
    }

    @Override
    public Atendimento.Operacao encaixe(String conta, Atendimento.Encaixe encaixe, int duracaoMin, String ator) {
        return transacao.execute(status -> {
            Boolean valido = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM agenda.profissional p
                            JOIN agenda.profissional_especialidade pe ON pe.profissional_id = p.id
                            JOIN agenda.especialidade es ON es.id = pe.especialidade_id
                            WHERE p.id = :profissional AND p.conta_id = :conta AND p.ativo
                              AND es.id = :especialidade AND es.conta_id = :conta AND es.ativo)
                       AND (CAST(:convenio AS BIGINT) IS NULL OR EXISTS (SELECT 1 FROM agenda.convenio c
                            WHERE c.id = CAST(:convenio AS BIGINT) AND c.conta_id = :conta AND c.ativo))
                    """, conta(conta).addValue("profissional", encaixe.profissionalId())
                    .addValue("especialidade", encaixe.especialidadeId()).addValue("convenio", encaixe.convenioId()),
                    Boolean.class);
            if (!Boolean.TRUE.equals(valido)) {
                return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
            }
            Long pacienteId = jdbc.queryForObject("""
                    INSERT INTO agenda.paciente (conta_id, cpf, nascimento, nome, telefone)
                    VALUES (:conta, :cpf, :nascimento, :nome, :telefone)
                    ON CONFLICT (conta_id, cpf, nascimento) DO UPDATE SET
                        telefone = CASE WHEN EXCLUDED.telefone = '' THEN agenda.paciente.telefone ELSE EXCLUDED.telefone END
                    RETURNING id
                    """, conta(conta).addValue("cpf", encaixe.cpf())
                    .addValue("nascimento", java.sql.Date.valueOf(encaixe.nascimento()))
                    .addValue("nome", encaixe.nome()).addValue("telefone", encaixe.telefone()), Long.class);
            LocalDateTime agora = LocalDateTime.now(relogio).withNano(0);
            LocalDateTime inicio = agora;
            while (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM agenda.agendamento
                        WHERE conta_id = :conta AND profissional_id = :profissional AND inicio = :inicio AND\s"""
                            + OCUPA_HORARIO + ")", conta(conta).addValue("profissional", encaixe.profissionalId())
                    .addValue("inicio", Timestamp.valueOf(inicio)), Boolean.class))) {
                inicio = inicio.plusSeconds(1);
            }
            Long agendamentoId = jdbc.queryForObject("""
                    INSERT INTO agenda.agendamento (conta_id, profissional_id, especialidade_id, convenio_id, paciente_id,
                                                    inicio, fim, local, status, origem)
                    VALUES (:conta, :profissional, :especialidade, :convenio, :paciente, :inicio, :fim, '', 'AGUARDANDO',
                            'RECEPCAO')
                    RETURNING id
                    """, conta(conta).addValue("profissional", encaixe.profissionalId())
                    .addValue("especialidade", encaixe.especialidadeId()).addValue("convenio", encaixe.convenioId())
                    .addValue("paciente", pacienteId).addValue("inicio", Timestamp.valueOf(inicio))
                    .addValue("fim", Timestamp.valueOf(inicio.plusMinutes(duracaoMin))), Long.class);
            Long nrAtendimento = jdbc.queryForObject("""
                    INSERT INTO atendimento.atendimento_paciente
                        (conta_id, agendamento_id, paciente_id, profissional_id, especialidade_id, convenio_id,
                         ie_status_atendimento, dt_entrada, nm_usuario_entrada)
                    VALUES (:conta, :agendamento, :paciente, :profissional, :especialidade, :convenio,
                            'AGUARDANDO', :entrada, :usuario)
                    RETURNING nr_atendimento
                    """, conta(conta).addValue("agendamento", agendamentoId).addValue("paciente", pacienteId)
                    .addValue("profissional", encaixe.profissionalId()).addValue("especialidade", encaixe.especialidadeId())
                    .addValue("convenio", encaixe.convenioId()).addValue("entrada", Timestamp.valueOf(agora))
                    .addValue("usuario", usuario(ator)), Long.class);
            long nr = nrAtendimento == null ? 0 : nrAtendimento;
            eventoAgendamento(agendamentoId == null ? 0 : agendamentoId, "ENCAIXE", ator, "Atendimento " + nr);
            eventoAtendimento(nr, "CHEGADA", ator, "Encaixe");
            return Atendimento.Operacao.sucesso(nr);
        });
    }

    @Override
    public Atendimento.CadastrosEncaixe cadastrosEncaixe(String conta) {
        java.util.Map<Long, String> nomes = new java.util.LinkedHashMap<>();
        java.util.Map<Long, List<Atendimento.Opcao>> especialidades = new java.util.HashMap<>();
        jdbc.query("""
                SELECT p.id, p.nome, es.id AS especialidade_id, es.nome AS especialidade
                FROM agenda.profissional p
                JOIN agenda.profissional_especialidade pe ON pe.profissional_id = p.id
                JOIN agenda.especialidade es ON es.id = pe.especialidade_id AND es.conta_id = p.conta_id AND es.ativo
                WHERE p.conta_id = :conta AND p.ativo
                ORDER BY p.nome, p.id, es.nome
                """, conta(conta), rs -> {
            long id = rs.getLong("id");
            nomes.putIfAbsent(id, rs.getString("nome"));
            especialidades.computeIfAbsent(id, k -> new java.util.ArrayList<>())
                    .add(new Atendimento.Opcao(rs.getLong("especialidade_id"), rs.getString("especialidade")));
        });
        List<Atendimento.ProfissionalEncaixe> profissionais = nomes.entrySet().stream()
                .map(e -> new Atendimento.ProfissionalEncaixe(e.getKey(), e.getValue(), especialidades.get(e.getKey())))
                .toList();
        List<Atendimento.Opcao> convenios = jdbc.query(
                "SELECT id, nome FROM agenda.convenio WHERE conta_id = :conta AND ativo ORDER BY nome",
                conta(conta), (rs, i) -> new Atendimento.Opcao(rs.getLong("id"), rs.getString("nome")));
        return new Atendimento.CadastrosEncaixe(profissionais, convenios);
    }

    @Override
    public List<Atendimento.PacienteResumo> buscarPacientes(String conta, String termo, int limite) {
        String texto = termo == null ? "" : termo.trim();
        String digitos = texto.replaceAll("[.\\-\\s]", "");
        boolean porCpf = !digitos.isEmpty() && digitos.chars().allMatch(Character::isDigit);
        String filtro = porCpf ? "cpf LIKE :valor" : "nome ILIKE :valor";
        String valor = porCpf ? digitos + "%"
                : "%" + texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("SELECT id, nome, cpf, nascimento, telefone FROM agenda.paciente WHERE conta_id = :conta AND "
                        + filtro + " ORDER BY nome, nascimento LIMIT :limite",
                conta(conta).addValue("valor", valor).addValue("limite", limite),
                (rs, i) -> new Atendimento.PacienteResumo(rs.getLong("id"), rs.getString("nome"), rs.getString("cpf"),
                        rs.getDate("nascimento").toLocalDate(), rs.getString("telefone")));
    }

    // ---- favoritos e modelos do profissional ----

    @Override
    public List<Atendimento.MedicamentoFavorito> favoritos(String conta, long profissionalId) {
        return jdbc.query("""
                SELECT id, ds_medicamento, ds_apresentacao, ds_posologia, ie_via_aplicacao, ds_quantidade,
                       ie_uso_continuo, ds_observacao
                FROM atendimento.medicamento_favorito
                WHERE conta_id = :conta AND profissional_id = :profissional
                ORDER BY lower(ds_medicamento), lower(ds_apresentacao), id
                """, conta(conta).addValue("profissional", profissionalId), (rs, i) -> new Atendimento.MedicamentoFavorito(
                rs.getLong("id"), rs.getString("ds_medicamento"), rs.getString("ds_apresentacao"),
                rs.getString("ds_posologia"), rs.getString("ie_via_aplicacao"), rs.getString("ds_quantidade"),
                rs.getBoolean("ie_uso_continuo"), rs.getString("ds_observacao")));
    }

    @Override
    public long salvarFavorito(String conta, long profissionalId, Atendimento.MedicamentoFavorito favorito) {
        Long id = jdbc.queryForObject("""
                INSERT INTO atendimento.medicamento_favorito
                    (conta_id, profissional_id, ds_medicamento, ds_apresentacao, ds_posologia, ie_via_aplicacao,
                     ds_quantidade, ie_uso_continuo, ds_observacao)
                VALUES (:conta, :profissional, :medicamento, :apresentacao, :posologia, :via, :quantidade, :continuo,
                        :observacao)
                ON CONFLICT (conta_id, profissional_id, (lower(btrim(ds_medicamento))), (lower(btrim(ds_apresentacao))),
                             (lower(btrim(ds_posologia))))
                DO UPDATE SET ie_via_aplicacao = EXCLUDED.ie_via_aplicacao, ds_quantidade = EXCLUDED.ds_quantidade,
                              ie_uso_continuo = EXCLUDED.ie_uso_continuo, ds_observacao = EXCLUDED.ds_observacao
                RETURNING id
                """, conta(conta).addValue("profissional", profissionalId).addValue("medicamento", favorito.medicamento())
                .addValue("apresentacao", favorito.apresentacao()).addValue("posologia", favorito.posologia())
                .addValue("via", favorito.via()).addValue("quantidade", favorito.quantidade())
                .addValue("continuo", favorito.usoContinuo()).addValue("observacao", favorito.observacao()), Long.class);
        return id == null ? 0 : id;
    }

    @Override
    public boolean excluirFavorito(String conta, long profissionalId, long favoritoId) {
        return jdbc.update("DELETE FROM atendimento.medicamento_favorito "
                        + "WHERE id = :id AND conta_id = :conta AND profissional_id = :profissional",
                conta(conta).addValue("id", favoritoId).addValue("profissional", profissionalId)) == 1;
    }

    @Override
    public List<Atendimento.ModeloNota> modelos(String conta, long profissionalId) {
        return jdbc.query("""
                SELECT id, nm_atalho, ds_titulo, ds_texto FROM atendimento.modelo_nota
                WHERE conta_id = :conta AND profissional_id = :profissional ORDER BY nm_atalho
                """, conta(conta).addValue("profissional", profissionalId), (rs, i) -> new Atendimento.ModeloNota(
                rs.getLong("id"), rs.getString("nm_atalho"), rs.getString("ds_titulo"), rs.getString("ds_texto")));
    }

    @Override
    public Atendimento.Operacao salvarModelo(String conta, long profissionalId, Atendimento.ModeloNota modelo) {
        MapSqlParameterSource params = conta(conta).addValue("profissional", profissionalId)
                .addValue("atalho", modelo.atalho()).addValue("titulo", modelo.titulo()).addValue("texto", modelo.texto());
        try {
            if (modelo.id() == null) {
                Long id = jdbc.queryForObject("""
                        INSERT INTO atendimento.modelo_nota (conta_id, profissional_id, nm_atalho, ds_titulo, ds_texto)
                        VALUES (:conta, :profissional, :atalho, :titulo, :texto)
                        RETURNING id
                        """, params, Long.class);
                return Atendimento.Operacao.sucesso(id);
            }
            int alterados = jdbc.update("""
                    UPDATE atendimento.modelo_nota
                    SET nm_atalho = :atalho, ds_titulo = :titulo, ds_texto = :texto, atualizado_em = CURRENT_TIMESTAMP
                    WHERE id = :id AND conta_id = :conta AND profissional_id = :profissional
                    """, params.addValue("id", modelo.id()));
            return alterados == 1 ? Atendimento.Operacao.sucesso(modelo.id())
                    : Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        } catch (DuplicateKeyException ex) {
            return Atendimento.Operacao.falha(CodigoNegocio.ATALHO_DUPLICADO);
        }
    }

    @Override
    public boolean excluirModelo(String conta, long profissionalId, long modeloId) {
        return jdbc.update("DELETE FROM atendimento.modelo_nota WHERE id = :id AND conta_id = :conta AND profissional_id = :profissional",
                conta(conta).addValue("id", modeloId).addValue("profissional", profissionalId)) == 1;
    }
}

