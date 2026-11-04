package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import br.com.mandalore.agendamentoconversa.adapter.cliente.TasyConfigurado;
import br.com.mandalore.agendamentoconversa.application.domain.exception.ConsultaIndisponivelException;
import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PacienteCadastro;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;

@Component
@Conditional(TasyConfigurado.class)
public class TasyAgendaAdapter implements AgendaConsultaPort {

    private static final String IDENTIFICAR = """
            SELECT CD_PESSOA_FISICA, NM_PESSOA_FISICA
            FROM PESSOA_FISICA
            WHERE TRANSLATE(NR_CPF, '.-/', '') = :cpf
            AND CAST(DT_NASCIMENTO AS DATE) = :nascimento
            """;

    private static final String FUTUROS = """
            SELECT ac.NR_SEQUENCIA,
                   ac.DT_AGENDA,
                   ac.IE_STATUS_AGENDA,
                   em.DS_ESPECIALIDADE,
                   pf.NM_PESSOA_FISICA AS NM_MEDICO,
                   a.DS_AGENDA
            FROM AGENDA_CONSULTA ac
            JOIN AGENDA a ON a.CD_AGENDA = ac.CD_AGENDA
            LEFT JOIN ESPECIALIDADE_MEDICA em ON em.CD_ESPECIALIDADE = COALESCE(ac.CD_ESPECIALIDADE, a.CD_ESPECIALIDADE)
            LEFT JOIN PESSOA_FISICA pf ON pf.CD_PESSOA_FISICA = a.CD_PESSOA_FISICA
            WHERE ac.CD_PESSOA_FISICA = :paciente
            AND a.CD_TIPO_AGENDA = 3
            AND ac.IE_STATUS_AGENDA IN ('N', 'CN', 'M')
            AND ac.DT_AGENDA >= CURRENT_DATE
            ORDER BY ac.DT_AGENDA
            """;

    private static final String CONVENIOS = """
            SELECT c.CD_CONVENIO AS ID,
                   c.DS_CONVENIO AS NOME,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM AGENDA_CONSULTA ac
                       WHERE ac.CD_PESSOA_FISICA = :paciente
                       AND ac.CD_CONVENIO = c.CD_CONVENIO
                   ) THEN 1 ELSE 0 END AS USADO
            FROM CONVENIO c
            WHERE c.IE_SITUACAO = 'A'
            ORDER BY USADO DESC, c.DS_CONVENIO
            """;

    private static final String PROFISSIONAIS = """
            SELECT DISTINCT a.CD_PESSOA_FISICA AS ID,
                   pf.NM_PESSOA_FISICA AS NOME,
                   CASE WHEN EXISTS (
                       SELECT 1
                       FROM AGENDA_CONSULTA ac
                       WHERE ac.CD_PESSOA_FISICA = :paciente
                       AND ac.CD_AGENDA = a.CD_AGENDA
                   ) THEN 1 ELSE 0 END AS USADO
            FROM AGENDA a
            JOIN PESSOA_FISICA pf ON pf.CD_PESSOA_FISICA = a.CD_PESSOA_FISICA
            WHERE a.CD_TIPO_AGENDA = 3
            AND a.IE_SITUACAO = 'A'
            ORDER BY USADO DESC, NOME
            """;

    private static final String ESPECIALIDADES = """
            SELECT DISTINCT CAST(e.CD_ESPECIALIDADE AS VARCHAR(40)) AS ID, e.DS_ESPECIALIDADE AS NOME
            FROM AGENDA a
            JOIN ESPECIALIDADE_MEDICA e ON e.CD_ESPECIALIDADE = a.CD_ESPECIALIDADE
            WHERE a.CD_TIPO_AGENDA = 3
            AND a.IE_SITUACAO = 'A'
            AND e.IE_SITUACAO = 'A'
            AND (CAST(:medico AS VARCHAR(40)) IS NULL OR a.CD_PESSOA_FISICA = :medico)
            ORDER BY e.DS_ESPECIALIDADE
            """;

    private static final String DISPONIBILIDADE = """
            SELECT ac.NR_SEQUENCIA,
                   ac.CD_AGENDA,
                   ac.DT_AGENDA,
                   pf.NM_PESSOA_FISICA AS NM_MEDICO,
                   a.DS_AGENDA AS LOCAL,
                   em.DS_ESPECIALIDADE
            FROM AGENDA_CONSULTA ac
            JOIN AGENDA a ON a.CD_AGENDA = ac.CD_AGENDA
            LEFT JOIN PESSOA_FISICA pf ON pf.CD_PESSOA_FISICA = a.CD_PESSOA_FISICA
            LEFT JOIN ESPECIALIDADE_MEDICA em ON em.CD_ESPECIALIDADE = a.CD_ESPECIALIDADE
            WHERE ac.IE_STATUS_AGENDA = 'L'
            AND a.CD_TIPO_AGENDA = 3
            AND a.IE_SITUACAO = 'A'
            AND ac.DT_AGENDA >= :inicio
            AND ac.DT_AGENDA < :fim
            AND (
                CAST(:medico AS VARCHAR(40)) IS NULL
                OR a.CD_PESSOA_FISICA = CAST(:medico AS VARCHAR(40))
            )
            AND (
                CAST(:especialidade AS NUMERIC) IS NULL
                OR a.CD_ESPECIALIDADE = CAST(:especialidade AS NUMERIC)
            )
            AND (
                :periodo = 'tanto_faz'
                OR (:periodo = 'manha' AND CAST(TO_CHAR(ac.DT_AGENDA, 'HH24') AS INTEGER) < 12)
                OR (:periodo = 'tarde' AND CAST(TO_CHAR(ac.DT_AGENDA, 'HH24') AS INTEGER) >= 12)
            )
            ORDER BY ac.DT_AGENDA
            FETCH FIRST 8 ROWS ONLY
            """;

    // Horário livre de exame não traz procedimento no Tasy: o vínculo agenda x exame vem das marcações anteriores.
    private static final String EXAMES = """
            SELECT CAST(pi.NR_SEQUENCIA AS VARCHAR(40)) AS ID, pi.DS_PROC_EXAME AS NOME
            FROM PROC_INTERNO pi
            WHERE pi.IE_SITUACAO = 'A'
            AND EXISTS (
                SELECT 1
                FROM AGENDA_PACIENTE hist
                JOIN AGENDA a ON a.CD_AGENDA = hist.CD_AGENDA
                WHERE hist.NR_SEQ_PROC_INTERNO = pi.NR_SEQUENCIA
                AND a.CD_TIPO_AGENDA = 2
                AND a.IE_SITUACAO = 'A'
                AND EXISTS (
                    SELECT 1
                    FROM AGENDA_PACIENTE livre
                    WHERE livre.CD_AGENDA = a.CD_AGENDA
                    AND livre.IE_STATUS_AGENDA = 'L'
                    AND livre.HR_INICIO >= CURRENT_DATE
                )
            )
            ORDER BY pi.DS_PROC_EXAME
            """;

    private static final String DISPONIBILIDADE_EXAME = """
            SELECT ap.NR_SEQUENCIA,
                   ap.CD_AGENDA,
                   ap.HR_INICIO,
                   a.DS_AGENDA AS LOCAL
            FROM AGENDA_PACIENTE ap
            JOIN AGENDA a ON a.CD_AGENDA = ap.CD_AGENDA
            WHERE ap.IE_STATUS_AGENDA = 'L'
            AND a.CD_TIPO_AGENDA = 2
            AND a.IE_SITUACAO = 'A'
            AND ap.HR_INICIO >= :inicio
            AND ap.HR_INICIO < :fim
            AND (ap.NR_SEQ_PROC_INTERNO IS NULL OR ap.NR_SEQ_PROC_INTERNO = CAST(:exame AS NUMERIC))
            AND EXISTS (
                SELECT 1
                FROM AGENDA_PACIENTE hist
                WHERE hist.CD_AGENDA = a.CD_AGENDA
                AND hist.NR_SEQ_PROC_INTERNO = CAST(:exame AS NUMERIC)
            )
            AND (
                :periodo = 'tanto_faz'
                OR (:periodo = 'manha' AND CAST(TO_CHAR(ap.HR_INICIO, 'HH24') AS INTEGER) < 12)
                OR (:periodo = 'tarde' AND CAST(TO_CHAR(ap.HR_INICIO, 'HH24') AS INTEGER) >= 12)
            )
            ORDER BY ap.HR_INICIO
            FETCH FIRST 8 ROWS ONLY
            """;

    private static final String PROCEDIMENTO_EXAME = """
            SELECT CAST(NR_SEQUENCIA AS VARCHAR(40)) AS NR_SEQUENCIA,
                   CAST(CD_PROCEDIMENTO AS VARCHAR(40)) AS CD_PROCEDIMENTO,
                   CAST(IE_ORIGEM_PROCED AS VARCHAR(40)) AS IE_ORIGEM_PROCED,
                   CD_INTEGRACAO
            FROM PROC_INTERNO
            WHERE NR_SEQUENCIA = CAST(:exame AS NUMERIC)
            """;

    private static final String ORIENTACAO_EXAME = """
            SELECT DS_ORIENTACAO
            FROM PROC_INTERNO_ORIENTACAO
            WHERE NR_SEQ_PROC_INTERNO = CAST(:exame AS NUMERIC)
            AND IE_TIPO_ORIENTACAO = 'P'
            ORDER BY NR_SEQUENCIA
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TasyAgendaAdapter(@Qualifier("tasyJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.getJdbcTemplate().setQueryTimeout(3);
    }

    @Override
    public List<PacienteCadastro> identificar(String cpfDigitos, LocalDate nascimento) {
        return consultar(() -> jdbc.query(
                IDENTIFICAR,
                Map.of("cpf", cpfDigitos, "nascimento", java.sql.Date.valueOf(nascimento)),
                (rs, row) -> new PacienteCadastro(rs.getString("CD_PESSOA_FISICA"), rs.getString("NM_PESSOA_FISICA"))
        ));
    }

    @Override
    public List<AgendamentoAberto> agendamentosFuturos(String pacienteId) {
        return consultar(() -> jdbc.query(FUTUROS, Map.of("paciente", pacienteId), (rs, row) -> new AgendamentoAberto(
                rs.getString("NR_SEQUENCIA"),
                dataHora(rs, "DT_AGENDA"),
                rs.getString("DS_ESPECIALIDADE"),
                rs.getString("NM_MEDICO"),
                rs.getString("DS_AGENDA"),
                rs.getString("IE_STATUS_AGENDA")
        )));
    }

    @Override
    public List<ItemCatalogo> convenios(String pacienteId) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("paciente", pacienteId);
        return consultar(() -> jdbc.query(CONVENIOS, params, (rs, row) ->
                ItemCatalogo.real(rs.getString("ID"), rs.getString("NOME"), rs.getInt("USADO") == 1)));
    }

    @Override
    public List<ItemCatalogo> profissionais(String pacienteId) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("paciente", pacienteId);
        return consultar(() -> jdbc.query(PROFISSIONAIS, params, (rs, row) ->
                ItemCatalogo.real(rs.getString("ID"), rs.getString("NOME"), rs.getInt("USADO") == 1)));
    }

    @Override
    public List<ItemCatalogo> especialidades(String medicoId) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("medico", medicoId == null || medicoId.isBlank() ? null : medicoId);
        return consultar(() -> jdbc.query(ESPECIALIDADES, params, (rs, row) ->
                ItemCatalogo.real(rs.getString("ID"), rs.getString("NOME"), false)));
    }

    @Override
    public List<HorarioOferta> disponibilidade(FiltroDisponibilidade filtro) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("inicio", Timestamp.valueOf(filtro.inicio()));
        params.put("fim", Timestamp.valueOf(filtro.fim()));
        params.put("medico", filtro.medicoId() == null || filtro.medicoId().isBlank() ? null : filtro.medicoId());
        params.put("especialidade", filtro.especialidadeId());
        params.put("periodo", filtro.periodo() == null ? "tanto_faz" : filtro.periodo());
        return consultar(() -> jdbc.query(DISPONIBILIDADE, params, (rs, row) -> new HorarioOferta(
                rs.getString("NR_SEQUENCIA"),
                rs.getString("CD_AGENDA"),
                dataHora(rs, "DT_AGENDA"),
                rs.getString("NM_MEDICO"),
                rs.getString("DS_ESPECIALIDADE"),
                rs.getString("LOCAL")
        )));
    }

    @Override
    public List<ItemCatalogo> exames() {
        return consultar(() -> jdbc.query(EXAMES, Map.of(), (rs, row) ->
                ItemCatalogo.real(rs.getString("ID"), rs.getString("NOME"), false)));
    }

    @Override
    public List<HorarioOferta> disponibilidadeExame(
            String exameId,
            LocalDateTime inicio,
            LocalDateTime fim,
            String periodo
    ) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("exame", exameId);
        params.put("inicio", Timestamp.valueOf(inicio));
        params.put("fim", Timestamp.valueOf(fim));
        params.put("periodo", periodo == null ? "tanto_faz" : periodo);
        return consultar(() -> jdbc.query(DISPONIBILIDADE_EXAME, params, (rs, row) -> new HorarioOferta(
                rs.getString("NR_SEQUENCIA"),
                rs.getString("CD_AGENDA"),
                dataHora(rs, "HR_INICIO"),
                null,
                null,
                rs.getString("LOCAL")
        )));
    }

    @Override
    public ProcedimentoExame procedimentoExame(String exameId) {
        List<ProcedimentoExame> itens = consultar(() -> jdbc.query(
                PROCEDIMENTO_EXAME,
                Map.of("exame", exameId),
                (rs, row) -> new ProcedimentoExame(
                        rs.getString("NR_SEQUENCIA"),
                        rs.getString("CD_PROCEDIMENTO"),
                        rs.getString("IE_ORIGEM_PROCED"),
                        rs.getString("CD_INTEGRACAO")
                )
        ));
        return itens.isEmpty() ? null : itens.get(0);
    }

    @Override
    public String orientacaoExame(String exameId) {
        List<String> trechos = consultar(() -> jdbc.query(
                ORIENTACAO_EXAME,
                Map.of("exame", exameId),
                (rs, row) -> HtmlTasy.texto(rs.getString("DS_ORIENTACAO"))
        ));
        return String.join("\n\n", trechos.stream().filter(trecho -> !trecho.isBlank()).toList());
    }

    private <T> T consultar(Consulta<T> consulta) {
        try {
            return consulta.executar();
        } catch (RuntimeException ex) {
            throw new ConsultaIndisponivelException("Falha ao consultar o Tasy", ex);
        }
    }

    private java.time.LocalDateTime dataHora(ResultSet rs, String coluna) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(coluna);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    @FunctionalInterface
    private interface Consulta<T> {
        T executar();
    }
}
