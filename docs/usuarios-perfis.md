# Usuários e perfis da central

**Situação: feito em 06/11/2026, exceto "esqueci a senha"** (depende de envio de e-mail) e a migração da equipe
(passo 7). Ver [Base para várias clínicas](roadmap.md#base-para-várias-clínicas) no roteiro.

Antes, os logins da central (`/admin`) vinham só de variáveis de ambiente: `ADMIN_USER`, `CURADORIA_USER`,
`PRECOS_USER`, `AGENDA_USER`, `FUNIL_USER` e a recepção de cada clínica em `mandalore.clientes.<id>.recepcao.*`.
Funciona para uma clínica no betateste, mas não para várias clínicas com vários atendentes.

## Como usar

1. Precisa de `OBSERVABILITY_POSTGRES_ENABLED=true`. As tabelas do esquema `acesso` são criadas na subida.
2. Entre com o `ADMIN_USER` e abra **Usuários** no menu da central.
3. **Adicionar usuário:** nome, e-mail, perfil, clínicas (id da conta no Chatwoot) e, se precisar, telas a mais ou a
   menos. O Mandalore mostra uma senha provisória de 12 caracteres uma vez; passe à pessoa por um canal seguro.
4. A pessoa entra com o e-mail e a senha provisória e é levada a `/admin/senha` para criar a própria senha. Até
   trocar, nenhuma outra tela abre.
5. Depois, o ícone de chave no rodapé do menu troca a senha a qualquer momento.

O login confere primeiro as variáveis de ambiente e depois o banco (só quando o usuário digitado tem `@`). Com o
banco fora do ar, o `ADMIN_USER` continua entrando; as sessões dos usuários do banco recebem 503 até o banco voltar,
sem perder a sessão.

### Como foi montado

| Parte | Onde |
|---|---|
| Tabelas | `src/main/resources/db/acesso/schema.sql` |
| Regras (perfis, senha, quem altera quem) | `UsuarioService` |
| Banco | `PostgresUsuarioAdapter` |
| Login, sessão e troca obrigatória | `AdminAcesso`, `AdminAuthFilter`, `AdminController` (`/admin/senha`, `/admin/api/senha`) |
| Tela e API | `UsuariosController`, `resources/usuarios/index.html` |

- **Sessão:** cada usuário tem uma `versao`, que aumenta a cada alteração de acesso, senha ou situação. A sessão
  guarda a versão do login e cai quando ela muda. O filtro confere a versão no banco no máximo a cada 15 segundos
  por usuário; na instância que fez a alteração, na hora.
- **Editar perfil, clínicas ou telas encerra a sessão aberta da pessoa**, que entra de novo com as permissões novas.
  Mudar só o próprio nome ou e-mail não derruba a própria sessão.
- **Senha:** BCrypt. Mínimo de 10 e máximo de 72 caracteres; recusa as senhas comuns, as com menos de 4
  caracteres diferentes e as que contêm a parte do e-mail antes do `@`. Usuário inexistente gasta o mesmo tempo
  de verificação que um existente.
- **Regras de gestão:** só o administrador geral cria outro administrador geral, que não tem clínica. Os outros
  perfis precisam de pelo menos uma clínica. O gestor só enxerga e altera quem é exclusivamente das clínicas dele,
  só dá acesso a essas clínicas e só concede telas que ele mesmo tem. Ninguém desativa o próprio usuário nem muda o
  próprio perfil, clínicas ou telas.
- **Aba Acessos** da tela `/usuarios`: os últimos 200 eventos. O gestor vê os dos usuários das clínicas dele.

### Limitações conhecidas

- A **tabela de preços** e a **curadoria** ainda são únicas, sem clínica. O gestor da clínica com a tela de preços
  altera a tabela de todos. Com a segunda clínica, a tabela de preços precisa ganhar a conta.
- O Basic Auth (scripts) aceita usuários do banco, mas cada chamada verifica o BCrypt (cerca de 100 ms). Para
  integrações, prefira um login de variável ou o token do passo 4 da transição.
- As telas que gravam `login.usuario()` passam a gravar o e-mail para usuários do banco; os relatórios por
  pessoa (item 5) ainda não existem.

## Por que fazer

- **Criar usuário ou trocar senha** exige mexer no Coolify e reiniciar o Mandalore.
- **Login compartilhado:** a equipe inteira usa o mesmo usuário. A auditoria registra "financeiro" ou "funil",
  nunca a pessoa.
- **Perfil fixo:** cada variável abre um conjunto fixo de telas. Não dá para combinar "funil e preços da clínica X".
- **Sem ciclo de vida:** quem sai da clínica continua com a senha até alguém trocar a variável. Não há troca de
  senha pelo próprio usuário nem recuperação.
- **LGPD:** com mais de uma clínica, login compartilhado impede saber quem acessou o quê. A clínica é controladora
  dos dados e precisa desse registro.

## O que já existe e continua

- `AdminAcesso.Login` já carrega usuário, módulos (telas) e contas (clínicas). Todas as telas conferem por ele e
  gravam `login.usuario()` nas alterações (preços, agenda, funil, curadoria).
- Sessão de 8 horas, cookie só por HTTPS, bloqueio do IP após cinco senhas erradas e Basic Auth para scripts
  ([`orcamento-pedido.md`](orcamento-pedido.md), seção Central de administração).

Muda só **de onde vêm os logins**. As telas e o filtro de acesso continuam iguais.

## 1. Usuário

Tabela `acesso.usuario` no PostgreSQL:

| Coluna | Uso |
|---|---|
| `id` | chave |
| `nome`, `email` | identificação; `email` único e usado no login |
| `senha_hash` | BCrypt. A senha nunca é gravada nem registrada em log |
| `perfil` | perfil base (abaixo) |
| `ativo` | inativo não entra; sessões abertas caem na próxima requisição |
| `trocar_senha` | obriga a troca no próximo acesso (usuário novo ou senha resetada) |
| `criado_por`, `criado_em`, `ultimo_acesso_em` | auditoria |

Tabela `acesso.usuario_conta`: clínicas (contas do Chatwoot) que o usuário enxerga. Sem linha: só o administrador
geral, que enxerga todas.

## 2. Perfis

Perfil é um conjunto de telas. Os perfis são fixos no código, iguais para todas as clínicas:

| Perfil | Telas | Para quem |
|---|---|---|
| Administrador geral | todas, todas as clínicas, inclusive `/usuarios` | fornecedor do Mandalore |
| Gestor da clínica | todas as telas da clínica, inclusive `/usuarios` da clínica | gestor ou encarregado da clínica |
| Recepção | agenda e funil | recepção |
| Faturamento | funil e tabela de preços | faturamento e financeiro |
| Curadoria | curadoria e simulador | quem treina o assistente |

O usuário pode ganhar ou perder uma tela além do perfil (tabela `acesso.usuario_modulo`, com `concedido`
verdadeiro ou falso). Mais que isso, como perfis configuráveis pela clínica, fica fora do escopo.

O gestor da clínica cria e desativa usuários só da própria clínica e não pode dar perfil de administrador geral.

## 3. Tela `/usuarios`

Na central, para o administrador geral e o gestor da clínica:

- lista com nome, e-mail, perfil, clínicas, situação e último acesso;
- **Adicionar usuário:** nome, e-mail, perfil e clínicas. O Mandalore gera uma senha provisória, mostrada uma vez
  na tela, com troca obrigatória no primeiro acesso;
- **Editar:** perfil, clínicas e telas extras;
- **Desativar** e **reativar** (nunca apaga: a auditoria aponta para o usuário);
- **Resetar senha:** nova senha provisória, com troca obrigatória.

Janelas de confirmação no mesmo padrão da tabela de preços.

## 4. Senha

- Mínimo de 10 caracteres. Recusar as senhas mais comuns e a que contém o e-mail.
- **Trocar senha** no menu do usuário, pedindo a senha atual.
- **Esqueci a senha** na tela de login: link por e-mail, uso único, válido por 30 minutos. A resposta é a mesma
  exista ou não o e-mail. Depende de um envio de e-mail (SMTP); até lá, o gestor reseta pela tela.
- Trocar ou resetar a senha encerra as outras sessões do usuário.

## 5. Auditoria

Tabela `acesso.evento`: login, login recusado, troca de senha, reset, criação, alteração e desativação de usuário,
com quem fez, quando e o IP. Sem senha nem token.

As telas que já gravam `login.usuario()` passam a gravar o e-mail da pessoa. O relatório para a clínica mostra
quem acessou a central no período.

## 6. Transição

1. As credenciais das variáveis continuam valendo enquanto houver usuário nelas, para não trancar ninguém.
2. `ADMIN_USER` vira o acesso de emergência do fornecedor: entra mesmo com o banco fora do ar e fica registrado
   no log.
3. Depois que a equipe estiver cadastrada, apagar `CURADORIA_USER`, `PRECOS_USER`, `AGENDA_USER`, `FUNIL_USER` e as
   recepções do `clientes.properties`.
4. Basic Auth para scripts (importação de preços, OpenClaw) passa a usar um usuário próprio, sem tela, ou um
   token por integração.

## 7. Login pelo Chatwoot (depois)

O atendente já tem usuário no Chatwoot. A Community Edition não é provedor de SSO, mas dá para validar o agente
pela API (token de acesso do agente e conta). Faz sentido junto com o painel lateral (Dashboard App) do
[funil](funil-orcamento.md): o atendente abre o painel na conversa sem um segundo login. Até lá, os usuários são
do Mandalore.

## Fora do escopo

Perfis criados pela clínica, permissão por registro, autenticação em dois fatores e integração com diretório
(AD, Google Workspace). Dois fatores é o primeiro a entrar se uma clínica exigir.

## Passos de implantação

1. Feito: tabelas `acesso.usuario`, `acesso.usuario_conta`, `acesso.usuario_modulo` e `acesso.evento`.
2. Feito: `AdminAcesso` lê os usuários do banco, mantendo as variáveis como reserva.
3. Feito: tela `/usuarios` e perfil Gestor da clínica.
4. Feito: troca de senha no primeiro acesso e no menu.
5. Feito em parte: auditoria de acesso (aba Acessos). Falta o relatório para a clínica.
6. Esqueci a senha, quando houver envio de e-mail.
7. Migrar a equipe da clínica-laboratório e apagar as variáveis antigas.
