package br.com.mandalore.agendamentoconversa.application.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import br.com.mandalore.agendamentoconversa.application.domain.model.Usuario;

public interface UsuarioPort {

    /** E-mail sem diferença de maiúsculas. */
    Optional<Usuario.ComSenha> porEmail(String email);

    Optional<Usuario.ComSenha> porId(long id);

    List<Usuario.Cadastro> listar();

    /** Cria com troca de senha obrigatória. Retorna o id. */
    long criar(Usuario.Dados dados, String senhaHash, String autor);

    /** Toda alteração aumenta a versão: as sessões abertas do usuário deixam de valer. */
    void alterar(long id, Usuario.Dados dados);

    void ativo(long id, boolean ativo);

    void senha(long id, String senhaHash, boolean trocarSenha);

    void acessou(long id);

    /** Versão atual do usuário ativo; vazio quando inativo ou inexistente. */
    Optional<Integer> versaoSeAtivo(long id);

    void evento(Usuario.TipoEvento tipo, Long usuarioId, String alvo, String autor, String ip);

    /** Eventos mais recentes; {@code contas} não vazio: só de usuários dessas clínicas. */
    List<Usuario.Evento> eventos(Set<String> contas, int limite);
}
