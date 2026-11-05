# Configuração do Chatwoot para o Mandalore

Uma instalação do Chatwoot atende todos os clientes. Cada hospital é uma **conta**. O Mandalore é um só: ele lê o `account.id` que chega no webhook e responde naquela conversa.

A edição usada é a Community Edition (gratuita). Perfil personalizado, SSO e SLA não entram nesta edição.

## O que o Mandalore espera

O paciente escreve na caixa. O Chatwoot avisa o Mandalore. O Dialogflow monta a resposta. O Mandalore publica de volta na mesma conversa.

| Status da conversa | Quem fala |
|---|---|
| Pendente | Mandalore (Dialogflow) |
| Aberto | Atendente humano. O Mandalore registra `conversa_aberta` e não responde |
| Pendente de novo | O atendente devolveu a conversa ao bot. O Mandalore volta a responder |

O Mandalore só trata evento `message_created`, mensagem do paciente (`incoming`), não privada. Nota interna e resposta do próprio bot são ignoradas.

Quando a intent começa com `transbordo.`, o Mandalore publica o texto e muda o status para **aberto**.

## Variáveis do Mandalore

Ficam no `.env`. O `run.ps1` só as carrega na subida. Depois de alterar, reinicie o processo.

| Variável | Origem | Uso |
|---|---|---|
| `CHATWOOT_BASE_URL` | Origem do painel, sem caminho. Exemplo: `http://192.168.0.79` | O Mandalore chama a API |
| `CHATWOOT_API_TOKEN` | Token de acesso do bot daquela conta | Publicar mensagem e abrir a conversa para o atendente |
| `CHATWOOT_WEBHOOK_SECRET` | Senha criada por nós | O Chatwoot a envia em `?token=` na URL de saída. O Mandalore compara e recusa com 401 se não bater |

O campo **Secret** que o Chatwoot mostra na ficha do bot é outro valor. Ele assina o corpo com `X-Chatwoot-Signature`. O Mandalore não lê esse header. Não copie esse secret para `CHATWOOT_WEBHOOK_SECRET`.

Gere o segredo nosso uma vez por conta, por exemplo com 24 bytes em hexadecimal, e use o mesmo texto na URL e no `.env`.

## Usuários e perfis

Há dois papéis na Community Edition.

| Papel | O que faz |
|---|---|
| Administrador | Configura a conta: caixas, bots, agentes, etiquetas. Vê as conversas das caixas das quais participa |
| Agente | Atende conversas. Não abre Configurações da conta |

Um usuário pode estar em mais de uma conta, com papel diferente em cada uma. Quem não foi convidado para a conta do hospital não vê as conversas dela.

O administrador da instalação (quem criou o Chatwoot) acessa `/super_admin` e cria contas novas. Isso é diferente do administrador de uma conta.

### Convidar alguém

1. Entre na conta do hospital.
2. Abra **Configurações → Agentes → Adicionar agente**.
3. Informe o e-mail e o papel (Administrador ou Agente).
4. A pessoa recebe o convite, define a senha e passa a ver só essa conta.

Agente de transbordo precisa ser **Agente** (ou Administrador) desta conta e membro da caixa. Sem isso a conversa aberta não aparece para ninguém.

### Perfil pessoal

Cada pessoa ajusta o próprio perfil no avatar, canto superior: nome, foto, idioma e disponibilidade.

| Disponibilidade | Efeito |
|---|---|
| Online | Pode receber atribuição automática |
| Ocupado | Está no painel, mas o Chatwoot evita enviar conversa nova |
| Offline | Fora do expediente |

O nome e a foto do perfil aparecem para o paciente nas respostas humanas. O bot usa o nome e o avatar cadastrados na ficha do bot, não o perfil de um agente.

Notificações (som, e-mail, push) ficam em **Configurações do perfil → Notificações**.

## Logo e cores

São três superfícies. Só a do widget do site se altera pelo painel na Community Edition.

| Onde o paciente vê | O que mudar | Onde |
|---|---|---|
| Balão, cabeçalho e cor do chat no site | Cor, título de boas-vindas e subtítulo | Caixa **Site** → engrenagem → aba **Construtor do widget** |
| Nome e foto das respostas do bot | Nome e avatar do bot | **Configurações → Bots**, ficha do `bot-agenda` |
| Nome e foto quando um humano responde | Nome e foto do perfil | Avatar da pessoa → perfil |
| Logo da tela de login, do painel e o texto "Powered by" | Marca da instalação | Não há tela na Community Edition. A troca é no arquivo da imagem ou no registro `InstallationConfig`, abaixo |

A cor vale por caixa. Dois hospitais na mesma instalação podem ter widgets de cores diferentes. WhatsApp não usa essa cor: lá a marca é a do aplicativo do paciente.

No construtor do widget a prévia é ao vivo. Salve e recarregue a página do site (ou o Codepen) para o script pegar a cor nova.

A saudação do canal, se estiver ligada na caixa, é uma mensagem automática do Chatwoot antes do Mandalore. Para o primeiro texto ser o do Dialogflow, deixe essa saudação desligada.

### Marca da instalação por código

O logo da tela de login e o nome no título são da instalação inteira, não de uma conta. Isso se faz no servidor do Chatwoot (`192.168.0.79`), na pasta do `docker-compose`, não no projeto Mandalore.

Dentro do container `rails` os arquivos ficam em `/app/public/brand-assets/`:

| Arquivo | Onde aparece |
|---|---|
| `logo.svg` | Login e painel, tema claro |
| `logo_dark.svg` | Login e painel, tema escuro |
| `logo_thumbnail.svg` | Favicon. Use 512×512 |

Na pasta do compose, com os SVG novos ao lado:

```bash
docker compose cp logo.svg rails:/app/public/brand-assets/logo.svg
docker compose cp logo_dark.svg rails:/app/public/brand-assets/logo_dark.svg
docker compose cp logo_thumbnail.svg rails:/app/public/brand-assets/logo_thumbnail.svg
docker compose restart rails sidekiq
```

O caminho configurado continua `/brand-assets/logo.svg`, então a troca do arquivo basta. Recarregue o painel com cache limpo.

Para mudar o nome e o link "Powered by", no mesmo servidor abra o console e cole o bloco. Saia com `exit`.

```bash
docker compose exec rails bundle exec rails c
```

```ruby
{
  'INSTALLATION_NAME' => 'Agenda',
  'BRAND_NAME' => 'Agenda',
  'BRAND_URL' => 'https://exemplo.com',
  'WIDGET_BRAND_URL' => 'https://exemplo.com'
}.each do |nome, valor|
  InstallationConfig.find_by!(name: nome).update!(value: valor)
end
```

Essas chaves vêm travadas: não aparecem em `/super_admin`. Não crie um registro novo com o mesmo nome; isso quebra por chave duplicada. Edite o que já existe.

Uma atualização da imagem pode repor o SVG original. Guarde os arquivos da marca fora do container e copie de novo depois do upgrade. A cor do widget de cada hospital não entra nisso: ela fica na caixa.

## Passo a passo de uma conta

Repita isto para cada hospital. A imagem Docker e a `CHATWOOT_BASE_URL` não mudam.

### 1. Conta

Crie a conta (cadastro ou `/super_admin`). Anote o id numérico. No piloto a conta é a `2`, visível na URL `/app/accounts/2/...`. O Mandalore não grava esse id na configuração: ele vem em cada webhook.

### 2. Caixa de entrada

**Configurações → Caixas de entrada → Adicionar caixa de entrada.**

No piloto use **Site**. Informe nome e domínio. O script do widget fica na aba **Configuração**. Para testar sem site, use **Abrir no Codepen** nessa aba.

Quando o hospital tiver número, crie outra caixa do tipo **WhatsApp** e vincule o mesmo tipo de bot. O Mandalore não escolhe o canal.

Na mesma caixa:

- Desligue **Coletar e-mail**. Com essa opção e sem agente online, o widget pede e-mail e a mensagem não vira webhook.
- Desligue o formulário de pré-chat, se estiver ativo.
- Em **Configuração do bot**, escolha o bot desta conta e salve. Sem esse vínculo a conversa nasce **aberta** e o Mandalore responde com `conversa_aberta`, sem chamar o Dialogflow.

Inclua na caixa os agentes que podem ver essas conversas. Agente fora da caixa não a enxerga.

### 3. Bot

**Configurações → Bots → Adicionar bot.** Nome sugerido: `bot-agenda`.

No campo **URL de saída**, use um endereço que o processo do Chatwoot consiga abrir. O formulário só aceita URL que comece com `http://` ou `https://` e rejeita IP de rede local (`192.168...`).

| Ambiente | URL de saída |
|---|---|
| Mandalore na sua máquina e Chatwoot em outro servidor | HTTPS público até a porta 8080. No piloto, o túnel ngrok: `https://<subdominio>.ngrok-free.app/chatwoot?token=<CHATWOOT_WEBHOOK_SECRET>` |
| Mandalore e o container na mesma máquina | `http://host.docker.internal:8080/chatwoot?token=<CHATWOOT_WEBHOOK_SECRET>` |
| Cliente | `https://<host-do-mandalore>/chatwoot?token=<segredo-dessa-conta>` |

`localhost` dentro do container é o próprio Chatwoot, não o Mandalore. O túnel ngrok cai quando o processo fecha; no cliente a URL tem de ser fixa.

Depois de salvar, copie o **token de acesso** do bot para `CHATWOOT_API_TOKEN`. Use o token do bot, não o de um agente. Assim a resposta aparece como o bot.

O **Secret** gerado pelo Chatwoot fica só no painel.

### 4. Prova

1. Mandalore no ar (`run.ps1`) e, no piloto local, o ngrok apontando para a porta 8080.
2. Abra o widget (Codepen ou o site).
3. Envie `oi` numa conversa nova.
4. No terminal do Mandalore deve aparecer `conversation_id=` e `intent=texto.saudacao` (ou outra intent), com `status=sucesso`.
5. No painel, filtre **Pendente**. A resposta do Dialogflow está nessa conversa.

| Log | Significado |
|---|---|
| `intent=texto.saudacao` (ou outra) e `status=sucesso` | Caminho fechado |
| `codigo=conversa_aberta` | A conversa está aberta. Mude para pendente ou crie outra depois de vincular o bot |
| Nenhuma linha nova | O Chatwoot não alcançou o Mandalore. Confira a URL de saída e se o túnel ou o HTTPS está no ar |
| HTTP 401 | O `token` da URL e `CHATWOOT_WEBHOOK_SECRET` diferem |

`Started AgendamentoConversaApplication` é só a subida do processo. Não é conexão com o Oracle nem mensagem do paciente. O pool do Tasy abre na primeira consulta.

## Transbordo

O paciente pede atendente. O Dialogflow devolve intent `transbordo.*`. O Mandalore publica o texto, atribui a equipe do assunto e marca a conversa como **aberta**. Ela sai do filtro Pendente e entra em Aberto, para os agentes da caixa.

### Equipe por assunto

O assunto vem de palavras da mensagem do transbordo ou da última mensagem da conversa que tinha assunto:

| Equipe | Palavras (sem acento) | Variável com o ID |
|---|---|---|
| Faturamento/Convênios | convenio, guia, autoriza, carteirinha, reembolso, nota fiscal, boleto, pagamento, cobranca, fatura, glosa, orcamento | `CHATWOOT_EQUIPE_FATURAMENTO` |
| Laboratório | resultado, laudo, coleta, recoleta, amostra, jejum, preparo, urina, fezes, sangue | `CHATWOOT_EQUIPE_LABORATORIO` |
| Recepção | qualquer outro assunto | `CHATWOOT_EQUIPE_RECEPCAO` |

O ID é o número na URL da equipe em **Configurações → Equipes** (`.../settings/teams/3/edit` → `3`). Variável vazia: a conversa abre sem equipe. Falha ao atribuir não impede a abertura.

Com **Permitir atribuição automática** ligado na equipe, o Chatwoot entrega a conversa a um membro dela. O membro precisa ser também colaborador da caixa.

O atendente responde pelo painel. O paciente vê o nome do perfil de quem escreveu. Para devolver ao bot, o atendente muda o status de volta para **Pendente**.

## Próximo hospital

1. Nova conta.
2. Nova caixa e novo bot, com outro `CHATWOOT_WEBHOOK_SECRET` na URL.
3. Outro token de acesso.
4. Agentes convidados só nessa conta.
5. No Mandalore, cadastre a conta no `clientes.properties` (fora do repositório; outro caminho em `CLIENTES_PROPERTIES_FILE`; não use variável que comece com `MANDALORE_CLIENTES_`, porque o Spring a lê como uma conta):

```properties
mandalore.clientes.3.chatwoot-token=<token do bot da conta 3>
mandalore.clientes.3.webhook-secret=<segredo da URL de saída da conta 3>
mandalore.clientes.3.tasy.url=jdbc:oracle:thin:@//<host>:1521/<servico>
mandalore.clientes.3.tasy.username=<usuario>
mandalore.clientes.3.tasy.password=<senha>
```

| Situação | Efeito |
|---|---|
| Conta cadastrada | Usa o token, o segredo e o Oracle dela. O segredo global não vale para essa conta |
| Conta sem cadastro | Usa `CHATWOOT_API_TOKEN`, `CHATWOOT_WEBHOOK_SECRET` e o Oracle do `.env` |
| Conta sem Oracle próprio e sem Oracle no `.env` | A consulta ao Tasy falha. O Mandalore não lê a agenda de outro hospital |

O Oracle é escolhido pela sessão do Dialogflow (`cw-<conta>-<conversa>`). Reinicie o processo depois de alterar o arquivo.

## Canais

WhatsApp, Instagram, Facebook Messenger e site entram pelo mesmo `/chatwoot`. O Mandalore lê `conversation.channel` e divide a resposta conforme o limite do canal:

| Canal no Chatwoot | Limite por mensagem |
|---|---|
| `Channel::Whatsapp` | 1500 caracteres |
| `Channel::Instagram` (ou conversa `instagram_direct_message`) | 900 caracteres e no máximo 1000 bytes |
| `Channel::FacebookPage` | 2000 caracteres |
| `Channel::WebWidget` e outros | 1500 caracteres |

O log de cada mensagem traz `canal=`.
