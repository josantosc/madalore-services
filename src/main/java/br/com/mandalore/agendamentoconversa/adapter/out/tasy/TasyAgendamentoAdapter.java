package br.com.mandalore.agendamentoconversa.adapter.out.tasy;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClienteAtual;
import br.com.mandalore.agendamentoconversa.adapter.cliente.TasyConfigurado;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;

/**
 * Grava o agendamento direto no Oracle do Tasy do cliente da requisição.
 * Não insere nem altera PESSOA_FISICA: o cadastro do paciente continua sendo do Tasy.
 */
@Component
@Profile("!local-postgres")
@ConditionalOnProperty(name = "agendamento.escrita", havingValue = "tasy")
@Conditional(TasyConfigurado.class)
public class TasyAgendamentoAdapter implements AgendamentoEscritaPort {

    private static final Logger LOG = LoggerFactory.getLogger(TasyAgendamentoAdapter.class);

    private static final String AGENDAR = """
            UPDATE AGENDA_CONSULTA
            SET IE_STATUS_AGENDA = 'N',
                CD_PESSOA_FISICA = :paciente,
                NM_PACIENTE = :nome,
                DT_NASCIMENTO_PAC = :nascimento,
                CD_CONVENIO = NVL(:convenio, CD_CONVENIO),
                DT_AGENDAMENTO = SYSDATE,
                DT_ATUALIZACAO = SYSDATE,
                NM_USUARIO = :usuario
            WHERE CD_AGENDA = :agenda
              AND DT_AGENDA = :data
              AND IE_STATUS_AGENDA = 'L'
            """;

    private static final String AGENDAR_EXAME = """
            UPDATE AGENDA_PACIENTE
            SET IE_STATUS_AGENDA = 'N',
                CD_PESSOA_FISICA = :paciente,
                NM_PACIENTE = :nome,
                DT_NASCIMENTO_PAC = :nascimento,
                CD_CONVENIO = NVL(:convenio, CD_CONVENIO),
                CD_PROCEDIMENTO = :procedimento,
                IE_ORIGEM_PROCED = :origem,
                NR_SEQ_PROC_INTERNO = :procInterno,
                DT_ATUALIZACAO = SYSDATE,
                NM_USUARIO = :usuario
            WHERE CD_AGENDA = :agenda
              AND HR_INICIO = :data
              AND IE_STATUS_AGENDA = 'L'
            """;

    private static final String JA_AGENDADO = """
            SELECT COUNT(*)
            FROM AGENDA_CONSULTA
            WHERE CD_AGENDA = :agenda
              AND DT_AGENDA = :data
              AND IE_STATUS_AGENDA IN ('N', 'CN', 'M')
              AND (CD_PESSOA_FISICA = :paciente OR (:paciente IS NULL AND NM_PACIENTE = :nome))
            """;

    private static final String EXAME_JA_AGENDADO = """
            SELECT COUNT(*)
            FROM AGENDA_PACIENTE
            WHERE CD_AGENDA = :agenda
              AND HR_INICIO = :data
              AND IE_STATUS_AGENDA IN ('N', 'CN', 'M')
              AND (CD_PESSOA_FISICA = :paciente OR (:paciente IS NULL AND NM_PACIENTE = :nome))
            """;

    private static final String CONFIRMAR = """
            UPDATE AGENDA_CONSULTA
            SET IE_STATUS_AGENDA = 'CN',
                DT_CONFIRMACAO = SYSDATE,
                DT_ATUALIZACAO = SYSDATE,
                NM_USUARIO = :usuario
            WHERE NR_SEQUENCIA = :sequencia
              AND CD_PESSOA_FISICA = :paciente
              AND IE_STATUS_AGENDA IN ('N', 'CN', 'M')
            """;

    // Cancela sem devolver o horário para 'L': mantém o paciente no histórico e o horário sai da oferta.
    private static final String CANCELAR = """
            UPDATE AGENDA_CONSULTA
            SET IE_STATUS_AGENDA = 'C',
                DT_CANCELAMENTO = SYSDATE,
                DT_ATUALIZACAO = SYSDATE,
                NM_USUARIO = :usuario
            WHERE NR_SEQUENCIA = :sequencia
              AND CD_PESSOA_FISICA = :paciente
              AND IE_STATUS_AGENDA IN ('N', 'CN', 'M')
            """;

    private static final String JA_CANCELADO = """
            SELECT COUNT(*)
            FROM AGENDA_CONSULTA
            WHERE NR_SEQUENCIA = :sequencia
              AND CD_PESSOA_FISICA = :paciente
              AND IE_STATUS_AGENDA = 'C'
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final String usuario;
    private final Map<String, ResultadoEscrita> gravados = new ConcurrentHashMap<>();

    public TasyAgendamentoAdapter(
            @Qualifier("tasyJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Value("${tasy.escrita.usuario:MANDALORE}") String usuario
    ) {
        this.jdbc = jdbc;
        this.usuario = usuario;
    }

    @Override
    @Transactional
    public ResultadoEscrita agendar(PedidoAgendar pedido) {
        return idempotente(pedido.chaveIdempotencia(), () -> reservar(pedido));
    }

    @Override
    @Transactional
    public ResultadoEscrita confirmar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        return idempotente(chaveIdempotencia, () -> {
            int alterados = jdbc.update(CONFIRMAR, parametrosAgendamento(pacienteId, nrSequencia));
            return unico(alterados, CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        });
    }

    @Override
    @Transactional
    public ResultadoEscrita cancelar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        return idempotente(chaveIdempotencia, () -> cancelarExistente(pacienteId, nrSequencia));
    }

    @Override
    @Transactional
    public ResultadoEscrita remarcar(
            String chaveIdempotencia,
            String pacienteId,
            String nrAtual,
            PedidoAgendar novoHorario
    ) {
        return idempotente(chaveIdempotencia, () -> {
            ResultadoEscrita reserva = reservar(novoHorario);
            if (reserva.codigo() != CodigoNegocio.SUCESSO) {
                return reserva;
            }
            ResultadoEscrita cancelamento = cancelarExistente(pacienteId, nrAtual);
            if (cancelamento.codigo() != CodigoNegocio.SUCESSO) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return cancelamento;
        });
    }

    private ResultadoEscrita reservar(PedidoAgendar pedido) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("paciente", pedido.semCadastro() ? null : pedido.pacienteId(), Types.VARCHAR)
                .addValue("nome", pedido.nome(), Types.VARCHAR)
                .addValue("nascimento", pedido.nascimento(), Types.DATE)
                .addValue("convenio", numero(pedido.convenioId()), Types.NUMERIC)
                .addValue("agenda", new BigDecimal(pedido.cdAgenda()))
                .addValue("data", pedido.dataAgenda(), Types.TIMESTAMP)
                .addValue("usuario", usuario);
        String atualizar = AGENDAR;
        String conferir = JA_AGENDADO;
        if (pedido.isExame()) {
            parametros.addValue("procedimento", new BigDecimal(pedido.exame().cdProcedimento()))
                    .addValue("origem", new BigDecimal(pedido.exame().ieOrigemProced()))
                    .addValue("procInterno", new BigDecimal(pedido.exame().nrSeqProcInterno()));
            atualizar = AGENDAR_EXAME;
            conferir = EXAME_JA_AGENDADO;
        }
        int alterados = jdbc.update(atualizar, parametros);
        if (alterados == 0 && contar(conferir, parametros) > 0) {
            return new ResultadoEscrita(CodigoNegocio.SUCESSO);
        }
        return unico(alterados, CodigoNegocio.HORARIO_INDISPONIVEL);
    }

    private ResultadoEscrita cancelarExistente(String pacienteId, String nrSequencia) {
        MapSqlParameterSource parametros = parametrosAgendamento(pacienteId, nrSequencia);
        int alterados = jdbc.update(CANCELAR, parametros);
        if (alterados == 0 && contar(JA_CANCELADO, parametros) > 0) {
            return new ResultadoEscrita(CodigoNegocio.SUCESSO);
        }
        return unico(alterados, CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
    }

    private ResultadoEscrita unico(int alterados, CodigoNegocio seNenhum) {
        if (alterados == 1) {
            return new ResultadoEscrita(CodigoNegocio.SUCESSO);
        }
        if (alterados > 1) {
            LOG.warn("tasy_escrita linhas={} account_id={} desfeito", alterados, ClienteAtual.accountId());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResultadoEscrita(CodigoNegocio.INDISPONIVEL);
        }
        return new ResultadoEscrita(seNenhum);
    }

    private ResultadoEscrita idempotente(String chave, Supplier<ResultadoEscrita> operacao) {
        String escopo = ClienteAtual.accountId() + ":" + chave;
        ResultadoEscrita anterior = gravados.get(escopo);
        if (anterior != null) {
            return anterior;
        }
        ResultadoEscrita resultado = operacao.get();
        if (resultado.codigo() == CodigoNegocio.SUCESSO) {
            gravados.put(escopo, resultado);
        }
        return resultado;
    }

    private int contar(String sql, MapSqlParameterSource parametros) {
        Integer total = jdbc.queryForObject(sql, parametros, Integer.class);
        return total == null ? 0 : total;
    }

    private MapSqlParameterSource parametrosAgendamento(String pacienteId, String nrSequencia) {
        return new MapSqlParameterSource()
                .addValue("paciente", pacienteId, Types.VARCHAR)
                .addValue("sequencia", new BigDecimal(nrSequencia))
                .addValue("usuario", usuario);
    }

    private static BigDecimal numero(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(valor.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
