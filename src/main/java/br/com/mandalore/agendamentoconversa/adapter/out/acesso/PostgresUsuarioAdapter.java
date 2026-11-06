package br.com.mandalore.agendamentoconversa.adapter.out.acesso;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.UsuarioPort;

@Component
@ConditionalOnProperty(name = "observability.postgres.enabled", havingValue = "true")
public class PostgresUsuarioAdapter implements UsuarioPort {

    private static final String COLUNAS = """
            SELECT u.id, u.nome, u.email, u.senha_hash, u.perfil, u.ativo, u.trocar_senha, u.versao, u.criado_por,
                   u.criado_em, u.ultimo_acesso_em,
                   ARRAY(SELECT conta_id FROM acesso.usuario_conta c WHERE c.usuario_id = u.id ORDER BY conta_id) AS contas,
                   ARRAY(SELECT modulo FROM acesso.usuario_modulo m WHERE m.usuario_id = u.id AND m.concedido) AS extras,
                   ARRAY(SELECT modulo FROM acesso.usuario_modulo m WHERE m.usuario_id = u.id AND NOT m.concedido) AS retiradas
            FROM acesso.usuario u
            """;

    private static final String EMAIL_REPETIDO = "Já existe um usuário com esse e-mail";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transacao;

    public PostgresUsuarioAdapter(
            @Qualifier("observabilityJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Qualifier("observabilityDataSource") DataSource dataSource
    ) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public Optional<Usuario.ComSenha> porEmail(String email) {
        return jdbc.query(COLUNAS + " WHERE lower(u.email) = lower(:email)", new MapSqlParameterSource("email", email),
                PostgresUsuarioAdapter::comSenha).stream().findFirst();
    }

    @Override
    public Optional<Usuario.ComSenha> porId(long id) {
        return jdbc.query(COLUNAS + " WHERE u.id = :id", new MapSqlParameterSource("id", id),
                PostgresUsuarioAdapter::comSenha).stream().findFirst();
    }

    @Override
    public List<Usuario.Cadastro> listar() {
        return jdbc.query(COLUNAS + " ORDER BY u.ativo DESC, lower(u.nome)", new MapSqlParameterSource(),
                (rs, i) -> comSenha(rs, i).cadastro());
    }

    @Override
    public long criar(Usuario.Dados dados, String senhaHash, String autor) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("nome", dados.nome())
                .addValue("email", dados.email())
                .addValue("hash", senhaHash)
                .addValue("perfil", dados.perfil().name())
                .addValue("autor", limitar(autor));
        try {
            Long id = transacao.execute(status -> {
                long novo = jdbc.queryForObject("""
                        INSERT INTO acesso.usuario (nome, email, senha_hash, perfil, criado_por)
                        VALUES (:nome, :email, :hash, :perfil, :autor) RETURNING id
                        """, params, Long.class);
                gravarAcessos(novo, dados);
                return novo;
            });
            return id;
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException(EMAIL_REPETIDO);
        }
    }

    @Override
    public void alterar(long id, Usuario.Dados dados) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("nome", dados.nome())
                .addValue("email", dados.email())
                .addValue("perfil", dados.perfil().name());
        try {
            transacao.executeWithoutResult(status -> {
                int linhas = jdbc.update("""
                        UPDATE acesso.usuario SET nome = :nome, email = :email, perfil = :perfil, versao = versao + 1,
                            atualizado_em = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """, params);
                if (linhas == 0) {
                    throw new IllegalArgumentException("Usuário não encontrado");
                }
                MapSqlParameterSource porUsuario = new MapSqlParameterSource("id", id);
                jdbc.update("DELETE FROM acesso.usuario_conta WHERE usuario_id = :id", porUsuario);
                jdbc.update("DELETE FROM acesso.usuario_modulo WHERE usuario_id = :id", porUsuario);
                gravarAcessos(id, dados);
            });
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException(EMAIL_REPETIDO);
        }
    }

    private void gravarAcessos(long id, Usuario.Dados dados) {
        for (String conta : dados.contas()) {
            jdbc.update("INSERT INTO acesso.usuario_conta (usuario_id, conta_id) VALUES (:id, :conta)",
                    new MapSqlParameterSource().addValue("id", id).addValue("conta", conta));
        }
        for (String tela : dados.telasExtras()) {
            modulo(id, tela, true);
        }
        for (String tela : dados.telasRetiradas()) {
            if (!dados.telasExtras().contains(tela)) {
                modulo(id, tela, false);
            }
        }
    }

    private void modulo(long id, String tela, boolean concedido) {
        jdbc.update("INSERT INTO acesso.usuario_modulo (usuario_id, modulo, concedido) VALUES (:id, :modulo, :concedido)",
                new MapSqlParameterSource().addValue("id", id).addValue("modulo", tela).addValue("concedido", concedido));
    }

    @Override
    public void ativo(long id, boolean ativo) {
        jdbc.update("""
                UPDATE acesso.usuario SET ativo = :ativo, versao = versao + 1, atualizado_em = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("ativo", ativo));
    }

    @Override
    public void senha(long id, String senhaHash, boolean trocarSenha) {
        jdbc.update("""
                UPDATE acesso.usuario SET senha_hash = :hash, trocar_senha = :trocar, versao = versao + 1,
                    atualizado_em = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("hash", senhaHash)
                .addValue("trocar", trocarSenha));
    }

    @Override
    public void acessou(long id) {
        jdbc.update("UPDATE acesso.usuario SET ultimo_acesso_em = CURRENT_TIMESTAMP WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    @Override
    public Optional<Integer> versaoSeAtivo(long id) {
        return jdbc.queryForList("SELECT versao FROM acesso.usuario WHERE id = :id AND ativo",
                new MapSqlParameterSource("id", id), Integer.class).stream().findFirst();
    }

    @Override
    public void evento(Usuario.TipoEvento tipo, Long usuarioId, String alvo, String autor, String ip) {
        jdbc.update("""
                INSERT INTO acesso.evento (tipo, usuario_id, alvo, autor, ip) VALUES (:tipo, :usuario, :alvo, :autor, :ip)
                """, new MapSqlParameterSource()
                .addValue("tipo", tipo.name())
                .addValue("usuario", usuarioId)
                .addValue("alvo", alvo == null ? "" : limitar(alvo))
                .addValue("autor", limitar(autor))
                .addValue("ip", ip == null ? null : ip.length() > 64 ? ip.substring(0, 64) : ip));
    }

    @Override
    public List<Usuario.Evento> eventos(Set<String> contas, int limite) {
        MapSqlParameterSource params = new MapSqlParameterSource("limite", limite);
        String filtro = "";
        if (!contas.isEmpty()) {
            filtro = " WHERE usuario_id IN (SELECT usuario_id FROM acesso.usuario_conta WHERE conta_id IN (:contas))";
            params.addValue("contas", contas);
        }
        return jdbc.query("SELECT criado_em, tipo, alvo, autor, ip FROM acesso.evento" + filtro
                        + " ORDER BY criado_em DESC, id DESC LIMIT :limite", params,
                (rs, i) -> new Usuario.Evento(instante(rs.getTimestamp("criado_em")),
                        Usuario.TipoEvento.valueOf(rs.getString("tipo")), rs.getString("alvo"), rs.getString("autor"),
                        rs.getString("ip")));
    }

    private static Usuario.ComSenha comSenha(ResultSet rs, int linha) throws SQLException {
        Usuario.Cadastro cadastro = new Usuario.Cadastro(
                rs.getLong("id"),
                rs.getString("nome"),
                rs.getString("email"),
                Usuario.Perfil.valueOf(rs.getString("perfil")),
                rs.getBoolean("ativo"),
                rs.getBoolean("trocar_senha"),
                textos(rs.getArray("contas")),
                textos(rs.getArray("extras")),
                textos(rs.getArray("retiradas")),
                rs.getInt("versao"),
                rs.getString("criado_por"),
                instante(rs.getTimestamp("criado_em")),
                instante(rs.getTimestamp("ultimo_acesso_em")));
        return new Usuario.ComSenha(cadastro, rs.getString("senha_hash"));
    }

    private static Set<String> textos(Array array) throws SQLException {
        return array == null ? Set.of() : Set.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    private static Instant instante(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String limitar(String texto) {
        return texto.length() > 200 ? texto.substring(0, 200) : texto;
    }
}
