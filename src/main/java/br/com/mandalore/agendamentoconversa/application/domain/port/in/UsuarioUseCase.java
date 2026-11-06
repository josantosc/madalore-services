package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;

public interface UsuarioUseCase {

    /** Login da central: e-mail e senha conferem e o usuário está ativo. */
    Optional<Usuario.Cadastro> autenticar(String email, String senha);

    /** A sessão aberta na {@code versao} ainda vale (usuário ativo e sem alteração desde então). */
    boolean vigente(long id, int versao);

    void registrarLogin(long id, String email, String ip);

    Optional<Usuario.Cadastro> buscar(long id);

    void registrarLoginRecusado(String informado, String ip);

    List<Usuario.Cadastro> listar(Usuario.Ator ator);

    /** Retorna a senha provisória, mostrada uma vez. */
    String criar(Usuario.Ator ator, Usuario.Dados dados, String ip);

    void alterar(Usuario.Ator ator, long id, Usuario.Dados dados, String ip);

    void ativar(Usuario.Ator ator, long id, boolean ativo, String ip);

    /** Retorna a nova senha provisória. */
    String resetarSenha(Usuario.Ator ator, long id, String ip);

    /** Troca pelo próprio usuário. Retorna o cadastro com a nova versão. */
    Usuario.Cadastro trocarSenha(long id, String atual, String nova, String ip);

    List<Usuario.Evento> eventos(Usuario.Ator ator);

    /** Clínicas que aparecem nos cadastros. */
    Set<String> contasCadastradas();
}
