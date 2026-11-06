package br.com.mandalore.agendamentoconversa.adapter.out.acesso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;

@SpringBootTest(properties = {"admin.user=adm-it", "admin.password=senha-it"})
@AutoConfigureMockMvc
@ActiveProfiles("local-postgres")
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_POSTGRES_TESTS", matches = "true")
class PostgresUsuarioAdapterIT {

    private static final String ADMIN = "Basic " + Base64.getEncoder().encodeToString("adm-it:senha-it".getBytes(StandardCharsets.UTF_8));
    private static final String EMAILS = "it-usuario-%@teste.com";

    @Autowired
    private PostgresUsuarioAdapter usuarios;

    @Autowired
    @Qualifier("observabilityJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @AfterEach
    void limpar() {
        MapSqlParameterSource emails = new MapSqlParameterSource("emails", EMAILS);
        String ids = "(SELECT id FROM acesso.usuario WHERE email LIKE :emails)";
        jdbc.update("DELETE FROM acesso.evento WHERE usuario_id IN " + ids + " OR alvo LIKE :emails", emails);
        jdbc.update("DELETE FROM acesso.usuario_modulo WHERE usuario_id IN " + ids, emails);
        jdbc.update("DELETE FROM acesso.usuario_conta WHERE usuario_id IN " + ids, emails);
        jdbc.update("DELETE FROM acesso.usuario WHERE email LIKE :emails", emails);
    }

    @Test
    void gravaContasETelasEAumentaAVersaoACadaAlteracao() {
        long id = usuarios.criar(new Usuario.Dados("Ana", "it-usuario-ana@teste.com", Usuario.Perfil.RECEPCAO,
                Set.of("1", "2"), Set.of("PRECOS"), Set.of("FUNIL")), "hash", "adm");

        Usuario.Cadastro ana = usuarios.porEmail("IT-USUARIO-ANA@teste.com").orElseThrow().cadastro();
        assertThat(ana.id()).isEqualTo(id);
        assertThat(ana.contas()).containsExactlyInAnyOrder("1", "2");
        assertThat(ana.telas()).containsExactlyInAnyOrder("AGENDA", "PRECOS");
        assertThat(ana.trocarSenha()).isTrue();
        assertThat(usuarios.versaoSeAtivo(id)).contains(1);

        usuarios.alterar(id, new Usuario.Dados("Ana Souza", "it-usuario-ana@teste.com", Usuario.Perfil.FATURAMENTO,
                Set.of("2"), Set.of(), Set.of()));
        usuarios.senha(id, "outro", false);
        usuarios.ativo(id, false);

        Usuario.ComSenha alterada = usuarios.porId(id).orElseThrow();
        assertThat(alterada.cadastro().contas()).containsExactly("2");
        assertThat(alterada.cadastro().telas()).containsExactlyInAnyOrder("FUNIL", "PRECOS");
        assertThat(alterada.cadastro().versao()).isEqualTo(4);
        assertThat(alterada.senhaHash()).isEqualTo("outro");
        assertThat(usuarios.versaoSeAtivo(id)).isEmpty();
    }

    @Test
    void emailRepetidoSemDiferencaDeMaiusculas() {
        usuarios.criar(new Usuario.Dados("Ana", "it-usuario-ana@teste.com", Usuario.Perfil.RECEPCAO, Set.of("1"),
                Set.of(), Set.of()), "hash", "adm");

        assertThatThrownBy(() -> usuarios.criar(new Usuario.Dados("Outra", "IT-usuario-ANA@teste.com",
                Usuario.Perfil.RECEPCAO, Set.of("1"), Set.of(), Set.of()), "hash", "adm"))
                .hasMessage("Já existe um usuário com esse e-mail");
    }

    @Test
    void eventosDoGestorSoDosUsuariosDaClinica() {
        long ana = usuarios.criar(new Usuario.Dados("Ana", "it-usuario-ana@teste.com", Usuario.Perfil.RECEPCAO,
                Set.of("it-1"), Set.of(), Set.of()), "hash", "adm");
        long bia = usuarios.criar(new Usuario.Dados("Bia", "it-usuario-bia@teste.com", Usuario.Perfil.RECEPCAO,
                Set.of("it-2"), Set.of(), Set.of()), "hash", "adm");
        usuarios.evento(Usuario.TipoEvento.LOGIN, ana, "it-usuario-ana@teste.com", "it-usuario-ana@teste.com", "1.1.1.1");
        usuarios.evento(Usuario.TipoEvento.LOGIN, bia, "it-usuario-bia@teste.com", "it-usuario-bia@teste.com", "1.1.1.1");

        List<Usuario.Evento> eventos = usuarios.eventos(Set.of("it-1"), 50);

        assertThat(eventos).extracting(Usuario.Evento::alvo).containsExactly("it-usuario-ana@teste.com");
    }

    @Test
    void cicloCompletoPelaCentral() throws Exception {
        String criado = mvc.perform(post("/usuarios/api/usuarios").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nome":"Ana","email":"it-usuario-ana@teste.com","perfil":"RECEPCAO","contas":["1"]}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String provisoria = json.readTree(criado).get("senha").asText();

        MockHttpSession sessao = new MockHttpSession();
        mvc.perform(post("/admin/login").session(sessao).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("usuario", "it-usuario-ana@teste.com").param("senha", provisoria))
                .andExpect(redirectedUrl("/admin/senha"));
        sessao = sessaoDoLogin("it-usuario-ana@teste.com", provisoria);

        mvc.perform(get("/agenda").session(sessao)).andExpect(redirectedUrl("/admin/senha"));
        mvc.perform(post("/admin/api/senha").session(sessao).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"atual\":\"" + provisoria + "\",\"nova\":\"curta\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/api/senha").session(sessao).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"atual\":\"" + provisoria + "\",\"nova\":\"bolo de fubá quente\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/api/eu").session(sessao))
                .andExpect(jsonPath("$.usuario").value("it-usuario-ana@teste.com"))
                .andExpect(jsonPath("$.pessoa").value("Ana"))
                .andExpect(jsonPath("$.cadastrado").value(true))
                .andExpect(jsonPath("$.trocarSenha").value(false))
                .andExpect(jsonPath("$.administrador").value(false));
        mvc.perform(get("/usuarios/api/usuarios").session(sessao)).andExpect(status().isForbidden());

        JsonNode lista = json.readTree(mvc.perform(get("/usuarios/api/usuarios").header("Authorization", ADMIN))
                .andReturn().getResponse().getContentAsString());
        long id = 0;
        for (JsonNode u : lista.get("usuarios")) {
            if (u.get("email").asText().equals("it-usuario-ana@teste.com")) {
                id = u.get("id").asLong();
            }
        }
        mvc.perform(post("/usuarios/api/usuarios/" + id + "/ativo").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/admin/api/eu").session(sessao)).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("usuario", "it-usuario-ana@teste.com").param("senha", "bolo de fubá quente"))
                .andExpect(redirectedUrl("/admin/login?erro=senha"));

        List<String> tipos = jdbc.queryForList("SELECT tipo FROM acesso.evento WHERE alvo = :alvo ORDER BY id",
                new MapSqlParameterSource("alvo", "it-usuario-ana@teste.com"), String.class);
        assertThat(tipos).containsExactly("USUARIO_CRIADO", "LOGIN", "LOGIN", "SENHA_TROCADA", "USUARIO_DESATIVADO",
                "LOGIN_RECUSADO");
    }

    private MockHttpSession sessaoDoLogin(String usuario, String senha) throws Exception {
        return (MockHttpSession) mvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("usuario", usuario).param("senha", senha))
                .andReturn().getRequest().getSession(false);
    }
}
