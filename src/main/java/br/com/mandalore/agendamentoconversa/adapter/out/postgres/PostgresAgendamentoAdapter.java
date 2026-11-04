package br.com.mandalore.agendamentoconversa.adapter.out.postgres;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;

@Component
@Profile("local-postgres")
public class PostgresAgendamentoAdapter implements AgendamentoEscritaPort {

    private static final String AGENDAR = """
            UPDATE agenda_consulta
            SET ie_status_agenda = 'N',
                cd_pessoa_fisica = :paciente,
                nm_paciente = :nome,
                dt_nascimento_pac = :nascimento,
                cd_convenio = COALESCE(cd_convenio, 15),
                dt_agendamento = CURRENT_TIMESTAMP,
                dt_atualizacao = CURRENT_TIMESTAMP,
                nm_usuario = 'LOCAL'
            WHERE cd_agenda = :agenda
              AND dt_agenda = :data
              AND ie_status_agenda = 'L'
            """;

    private static final String AGENDAR_EXAME = """
            UPDATE agenda_paciente
            SET ie_status_agenda = 'N',
                cd_pessoa_fisica = :paciente,
                nm_paciente = :nome,
                dt_nascimento_pac = :nascimento,
                cd_convenio = COALESCE(cd_convenio, 15),
                cd_procedimento = :procedimento,
                ie_origem_proced = :origem,
                nr_seq_proc_interno = :procInterno,
                dt_atualizacao = CURRENT_TIMESTAMP,
                nm_usuario = 'LOCAL'
            WHERE cd_agenda = :agenda
              AND hr_inicio = :data
              AND ie_status_agenda = 'L'
            """;

    private static final String CONFIRMAR = """
            UPDATE agenda_consulta
            SET ie_status_agenda = 'CN',
                dt_confirmacao = CURRENT_TIMESTAMP,
                dt_atualizacao = CURRENT_TIMESTAMP,
                nm_usuario = 'LOCAL'
            WHERE nr_sequencia = :sequencia
              AND cd_pessoa_fisica = :paciente
              AND ie_status_agenda IN ('N', 'CN', 'M')
            """;

    private static final String CANCELAR = """
            UPDATE agenda_consulta
            SET ie_status_agenda = 'L',
                cd_pessoa_fisica = NULL,
                nm_paciente = NULL,
                dt_nascimento_pac = NULL,
                cd_convenio = NULL,
                dt_agendamento = NULL,
                dt_confirmacao = NULL,
                dt_cancelamento = CURRENT_TIMESTAMP,
                dt_atualizacao = CURRENT_TIMESTAMP,
                nm_usuario = 'LOCAL'
            WHERE nr_sequencia = :sequencia
              AND cd_pessoa_fisica = :paciente
              AND ie_status_agenda IN ('N', 'CN', 'M')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresAgendamentoAdapter(@Qualifier("tasyJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ResultadoEscrita agendar(PedidoAgendar pedido) {
        bloquearIdempotencia(pedido.chaveIdempotencia());
        ResultadoEscrita anterior = buscarResultado(pedido.chaveIdempotencia());
        if (anterior != null) {
            return anterior;
        }

        if (!pedido.semCadastro()) {
            upsertPaciente(pedido);
        }
        int alterados = reservarHorario(pedido);
        ResultadoEscrita resultado = resultado(
                alterados == 1 ? CodigoNegocio.SUCESSO : CodigoNegocio.HORARIO_INDISPONIVEL);
        gravarResultado(pedido.chaveIdempotencia(), resultado);
        return resultado;
    }

    @Override
    @Transactional
    public ResultadoEscrita confirmar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        bloquearIdempotencia(chaveIdempotencia);
        ResultadoEscrita anterior = buscarResultado(chaveIdempotencia);
        if (anterior != null) {
            return anterior;
        }

        int alterados = jdbc.update(CONFIRMAR, parametrosAgendamento(pacienteId, nrSequencia));
        ResultadoEscrita resultado = resultado(
                alterados == 1 ? CodigoNegocio.SUCESSO : CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        gravarResultado(chaveIdempotencia, resultado);
        return resultado;
    }

    @Override
    @Transactional
    public ResultadoEscrita cancelar(String chaveIdempotencia, String pacienteId, String nrSequencia) {
        bloquearIdempotencia(chaveIdempotencia);
        ResultadoEscrita anterior = buscarResultado(chaveIdempotencia);
        if (anterior != null) {
            return anterior;
        }

        int alterados = liberarHorario(pacienteId, nrSequencia);
        ResultadoEscrita resultado = resultado(
                alterados == 1 ? CodigoNegocio.SUCESSO : CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        gravarResultado(chaveIdempotencia, resultado);
        return resultado;
    }

    @Override
    @Transactional
    public ResultadoEscrita remarcar(
            String chaveIdempotencia,
            String pacienteId,
            String nrAtual,
            PedidoAgendar novoHorario
    ) {
        bloquearIdempotencia(chaveIdempotencia);
        ResultadoEscrita anterior = buscarResultado(chaveIdempotencia);
        if (anterior != null) {
            return anterior;
        }

        upsertPaciente(novoHorario);
        if (reservarHorario(novoHorario) != 1) {
            ResultadoEscrita indisponivel = resultado(CodigoNegocio.HORARIO_INDISPONIVEL);
            gravarResultado(chaveIdempotencia, indisponivel);
            return indisponivel;
        }
        if (liberarHorario(pacienteId, nrAtual) != 1) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return resultado(CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        }

        ResultadoEscrita sucesso = resultado(CodigoNegocio.SUCESSO);
        gravarResultado(chaveIdempotencia, sucesso);
        return sucesso;
    }

    private void upsertPaciente(PedidoAgendar pedido) {
        jdbc.update("""
                INSERT INTO pessoa_fisica (
                    cd_pessoa_fisica, ie_tipo_pessoa, nm_pessoa_fisica,
                    dt_nascimento, nr_cpf, nm_usuario, dt_atualizacao
                ) VALUES (
                    :paciente, 2, :nome, :nascimento, :cpf, 'LOCAL', CURRENT_TIMESTAMP
                )
                ON CONFLICT (cd_pessoa_fisica) DO UPDATE SET
                    nm_pessoa_fisica = EXCLUDED.nm_pessoa_fisica,
                    dt_nascimento = EXCLUDED.dt_nascimento,
                    nr_cpf = EXCLUDED.nr_cpf,
                    dt_atualizacao = CURRENT_TIMESTAMP
                """, Map.of(
                "paciente", pedido.pacienteId(),
                "nome", pedido.nome(),
                "nascimento", pedido.nascimento(),
                "cpf", pedido.cpf()
        ));
    }

    private int reservarHorario(PedidoAgendar pedido) {
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("paciente", pedido.semCadastro() ? null : pedido.pacienteId());
        parametros.put("nome", pedido.nome());
        parametros.put("nascimento", pedido.nascimento());
        parametros.put("agenda", new BigDecimal(pedido.cdAgenda()));
        parametros.put("data", pedido.dataAgenda());
        if (!pedido.isExame()) {
            return jdbc.update(AGENDAR, parametros);
        }
        parametros.put("procedimento", new BigDecimal(pedido.exame().cdProcedimento()));
        parametros.put("origem", new BigDecimal(pedido.exame().ieOrigemProced()));
        parametros.put("procInterno", new BigDecimal(pedido.exame().nrSeqProcInterno()));
        return jdbc.update(AGENDAR_EXAME, parametros);
    }

    private int liberarHorario(String pacienteId, String nrSequencia) {
        return jdbc.update(CANCELAR, parametrosAgendamento(pacienteId, nrSequencia));
    }

    private Map<String, Object> parametrosAgendamento(String pacienteId, String nrSequencia) {
        return Map.of(
                "paciente", pacienteId,
                "sequencia", new BigDecimal(nrSequencia)
        );
    }

    private void bloquearIdempotencia(String chave) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtext(:chave))",
                Map.of("chave", chave),
                (ResultSetExtractor<Void>) rs -> null
        );
    }

    private ResultadoEscrita buscarResultado(String chave) {
        List<String> codigos = jdbc.query(
                "SELECT codigo FROM agendamento_operacao WHERE chave = :chave",
                Map.of("chave", chave),
                (rs, rowNum) -> rs.getString("codigo")
        );
        return codigos.isEmpty() ? null : resultado(CodigoNegocio.valueOf(codigos.get(0)));
    }

    private void gravarResultado(String chave, ResultadoEscrita resultado) {
        jdbc.update("""
                INSERT INTO agendamento_operacao (chave, codigo)
                VALUES (:chave, :codigo)
                ON CONFLICT (chave) DO NOTHING
                """, Map.of("chave", chave, "codigo", resultado.codigo().name()));
    }

    private ResultadoEscrita resultado(CodigoNegocio codigo) {
        return new ResultadoEscrita(codigo);
    }
}
