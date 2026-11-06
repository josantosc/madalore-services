package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAcesso;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAuthFilter;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.Modulo;
import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.UsuarioUseCase;

import jakarta.servlet.http.HttpServletRequest;

/** Tela de usuários da central (/usuarios). O gestor da clínica só enxerga e altera usuários das clínicas dele. */
@RestController
@ConditionalOnProperty(name = "observability.postgres.enabled", havingValue = "true")
public class UsuariosController {

    private final UsuarioUseCase usuarios;
    private final Set<String> contasConfiguradas;

    public UsuariosController(UsuarioUseCase usuarios, Environment env) {
        this.usuarios = usuarios;
        this.contasConfiguradas = Set.copyOf(Binder.get(env)
                .bind("mandalore.clientes", Bindable.mapOf(String.class, ClientesProperties.Cliente.class))
                .orElse(Map.of())
                .keySet());
    }

    public record UsuarioResposta(long id, String nome, String email, Usuario.Perfil perfil, boolean ativo,
                                  boolean trocarSenha, Set<String> contas, Set<String> telasExtras,
                                  Set<String> telasRetiradas, Set<String> telas, String criadoPor, Instant criadoEm,
                                  Instant ultimoAcessoEm) {

        static UsuarioResposta de(Usuario.Cadastro u) {
            return new UsuarioResposta(u.id(), u.nome(), u.email(), u.perfil(), u.ativo(), u.trocarSenha(),
                    new TreeSet<>(u.contas()), u.telasExtras(), u.telasRetiradas(), u.telas(), u.criadoPor(),
                    u.criadoEm(), u.ultimoAcessoEm());
        }
    }

    public record Opcao(String id, String titulo, List<String> telas) {
    }

    public record Lista(List<UsuarioResposta> usuarios, List<Opcao> perfis, List<Opcao> telas, Set<String> contas,
                        boolean geral, Long eu) {
    }

    public record AtivoRequest(boolean ativo) {
    }

    @GetMapping("/usuarios")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("usuarios/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/usuarios/api/usuarios", produces = MediaType.APPLICATION_JSON_VALUE)
    public Lista listar(@RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        Usuario.Ator ator = login.ator();
        List<Opcao> perfis = Arrays.stream(Usuario.Perfil.values())
                .filter(p -> ator.geral() || p != Usuario.Perfil.ADMINISTRADOR)
                .map(p -> new Opcao(p.name(), p.titulo(), p.telas()))
                .toList();
        List<Opcao> telas = Arrays.stream(Modulo.values())
                .filter(m -> ator.geral() || ator.telas().contains(m.name()))
                .map(m -> new Opcao(m.name(), m.titulo(), List.of()))
                .toList();
        Set<String> contas = new TreeSet<>(ator.contas());
        if (ator.geral()) {
            contas.addAll(contasConfiguradas);
            contas.addAll(usuarios.contasCadastradas());
        }
        return new Lista(usuarios.listar(ator).stream().map(UsuarioResposta::de).toList(), perfis, telas, contas,
                ator.geral(), login.id());
    }

    @PostMapping(value = "/usuarios/api/usuarios", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> criar(@RequestBody Usuario.Dados dados,
                                     @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login,
                                     HttpServletRequest request) {
        return Map.of("senha", usuarios.criar(login.ator(), dados, AdminAcesso.ip(request)));
    }

    @PutMapping(value = "/usuarios/api/usuarios/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> alterar(@PathVariable long id, @RequestBody Usuario.Dados dados,
                                        @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login,
                                        HttpServletRequest request) {
        usuarios.alterar(login.ator(), id, dados, AdminAcesso.ip(request));
        if (login.doBanco() && login.id() == id) {
            usuarios.buscar(id).ifPresent(atual ->
                    request.getSession().setAttribute(AdminAuthFilter.SESSAO, AdminAcesso.Login.de(atual)));
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/usuarios/api/usuarios/{id}/ativo", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> ativo(@PathVariable long id, @RequestBody AtivoRequest ativo,
                                      @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login,
                                      HttpServletRequest request) {
        usuarios.ativar(login.ator(), id, ativo.ativo(), AdminAcesso.ip(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/usuarios/api/usuarios/{id}/senha")
    public Map<String, String> resetarSenha(@PathVariable long id,
                                            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login,
                                            HttpServletRequest request) {
        return Map.of("senha", usuarios.resetarSenha(login.ator(), id, AdminAcesso.ip(request)));
    }

    @GetMapping(value = "/usuarios/api/acessos", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Usuario.Evento> acessos(@RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return usuarios.eventos(login.ator());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalido(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }
}
