# Implantação do OpenClaw por cliente

Roteiro para colocar os [relatórios pelo OpenClaw](relatorios-openclaw.md) no ar em um cliente novo. Siga na
ordem: cada passo depende do anterior. A primeira implantação (BioSaúde, 06/11/2026) levou cerca de duas horas por
causa dos problemas listados em [Problemas conhecidos](#problemas-conhecidos); seguindo este roteiro, leva uns 40
minutos.

## Como fica cada cliente

O OpenClaw segue a [decisão de escala](decisao-escala-clientes.md): uma pilha completa por cliente.

```
Paciente ──> Chatwoot ──> Mandalore ──> trilha (PostgreSQL)
                              │
                              │ GET /openclaw/** (Bearer OPENCLAW_TOKEN)
                              ▼
                          OpenClaw ──> Gemini (modelo)
                              │
                              ▼
                   Grupo da clínica no Telegram
```

| Peça | Uma por cliente | Por quê |
|---|---|---|
| Serviço OpenClaw no Coolify | Sim | Cada OpenClaw tem o próprio bot, grupo, agendamentos e chave de modelo. Um OpenClaw compartilhado misturaria clínicas no mesmo agente |
| Bot do Telegram | Sim | O nome aparece para a equipe da clínica; revogar um não derruba os outros |
| Projeto e chave do Gemini | Sim | Custo separado por cliente e revogação independente |
| `OPENCLAW_TOKEN` | Sim | Um token vazado só abre os números de uma instalação |
| Mandalore | Uma instalação por cliente | Os relatórios somam a instalação inteira. Veja [Limitação com várias clínicas](#limitação-com-várias-clínicas) |

## Ficha do cliente

Preencha antes de começar. Segredos ficam no cofre de senhas e no Coolify, nunca neste repositório nem no chat.

| Item | Exemplo (BioSaúde) | Onde é usado |
|---|---|---|
| Nome no título dos relatórios | `BioSaúde` | `ADMIN_NOME` no Mandalore |
| Endereço público do Mandalore | `https://bs-api.jscinfor.com` | `RELATORIOS_URL` e `MANDALORE_URL` |
| `OPENCLAW_TOKEN` | segredo de 64 caracteres | Mandalore e OpenClaw (mesmo valor) |
| Projeto do Google para o Gemini | `openclaw-biosaude` | Chave `GEMINI_API_KEY` |
| Bot do OpenClaw | `@joclow_bot` | `TELEGRAM_BOT_TOKEN` no OpenClaw |
| Grupo do Telegram | `BioSaudeNotification`, `-1004474298538` | `--to` dos agendamentos |
| Pessoas que podem falar com o bot | `349949574` | `TELEGRAM_GROUP_ALLOW_FROM` |
| Container do gateway | `openclaw-lssfzyzbmhcoygnx2pvcydd5` | Scripts e terminal |

## Passo 1. Mandalore

O Mandalore do cliente já precisa estar no ar ([deploy](deploy-coolify-hostinger.md)) com a trilha ligada.

1. Gere o `OPENCLAW_TOKEN` no PowerShell (funciona no Windows PowerShell 5.1):

   ```powershell
   $b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); -join ($b | ForEach-Object { $_.ToString("x2") })
   ```

   No Linux ou no Git Bash: `openssl rand -hex 32`.

2. No Coolify, no Mandalore, em **Environment Variables**:

   ```
   OBSERVABILITY_POSTGRES_ENABLED=true
   RELATORIOS_ENABLED=true
   OPENCLAW_TOKEN=<token>            (secret)
   RELATORIOS_URL=https://<api-do-cliente>
   ADMIN_NOME=<nome da clínica>
   ```

   Defina sempre o `ADMIN_NOME`. Sem ele, versões anteriores ao commit de correção mostram "BioSaÃºde" no título.

3. **Redeploy** e confira, sem token, que a API existe e está protegida:

   ```powershell
   Invoke-WebRequest https://<api-do-cliente>/openclaw/relatorios/curadoria
   ```

   | Resposta | Significado |
   |---|---|
   | 401 | Certo: API ligada e exigindo token |
   | 503 | `OPENCLAW_TOKEN` vazio |
   | 404 | `RELATORIOS_ENABLED` ou `OBSERVABILITY_POSTGRES_ENABLED` não está `true` |

## Passo 2. Chave do Gemini

1. Em [aistudio.google.com/apikey](https://aistudio.google.com/apikey), com a conta Google da empresa, crie um
   projeto para o cliente e uma chave nele.
2. Clique em **Set up billing** e vincule o faturamento. É obrigatório na prática:
   - o plano gratuito não libera os modelos Pro e devolve "billing error" na primeira mensagem;
   - no plano pago, o Google não usa o conteúdo para treinar modelos, o que importa para a LGPD.
3. No [Google Cloud Console](https://console.cloud.google.com/apis/credentials), restrinja a chave à
   **Generative Language API**.

Custo esperado com o Flash: centavos por semana (três agendamentos mais as perguntas da equipe).

## Passo 3. Bot do Telegram

1. No **@BotFather**: `/newbot`. Nome visível com a clínica (ex.: "BioSaúde Relatórios") e usuário terminado em
   `bot`. Guarde o token no cofre.
2. `/setjoingroups` → escolha o bot → **Enable** (permite adicionar a grupos).
3. Deixe o modo de privacidade como está. O bot vira administrador do grupo no passo 6 e passa a receber as
   mensagens; o OpenClaw só responde quando é mencionado.

Use um bot só para o OpenClaw. Não reaproveite o bot de outro cliente nem o do envio direto do Mandalore.

## Passo 4. Serviço OpenClaw no Coolify

1. **New → Resource → Service → OpenClaw** no mesmo servidor do Mandalore do cliente.
2. **Fixe a versão da imagem** (`coollabsio/openclaw:<versão>`). A versão testada é `2026.2.6`. Troque de versão
   só depois de testar em homologação: o catálogo de modelos e as opções de linha de comando mudam.
3. Em **Environment Variables** (as variáveis já existem vazias no template, exceto as duas do Mandalore):

   | Variável | Valor | Observação |
   |---|---|---|
   | `GEMINI_API_KEY` | chave do passo 2 (secret) | **Sem uma chave de modelo, o gateway reinicia sem parar** |
   | `OPENCLAW_PRIMARY_MODEL` | `google/gemini-flash-latest` | Formato `google/<modelo>`, não `models/<modelo>` |
   | `TELEGRAM_BOT_TOKEN` | token do passo 3 (secret) | |
   | `TELEGRAM_GROUP_ALLOW_FROM` | IDs das pessoas, separados por vírgula | Preenchido no passo 6 |
   | `MANDALORE_URL` | `https://<api-do-cliente>` | Criar |
   | `MANDALORE_OPENCLAW_TOKEN` | o mesmo `OPENCLAW_TOKEN` (secret) | Criar |

4. **Deploy**. Em **Logs**, o container `openclaw-<uuid>` deve mostrar:

   ```
   [gateway] agent model: google/gemini-flash-latest
   [telegram] [default] starting provider (@<bot>)
   ```

O template sobe **dois containers**: `openclaw-<uuid>` (o gateway, onde roda o OpenClaw) e `browser-<uuid>`
(um navegador com área de trabalho remota). Todo comando `openclaw` é no gateway. Se o terminal mostrar `HOME=/config`
e uma pasta `Desktop`, você está no navegador.

Pastas do gateway nesta imagem:

| Pasta | Conteúdo |
|---|---|
| `/data` | Volume persistente (sobrevive a restart e redeploy) |
| `/data/.openclaw/openclaw.json` | Configuração. O script de inicialização reescreve a parte do Telegram a partir das variáveis a cada start, sem apagar campos que ele não conhece |
| `/data/workspace/skills/` | Skills do workspace |
| `/tmp/openclaw/openclaw-AAAA-MM-DD.log` | Log detalhado do dia |

## Passo 5. Skill

Os volumes de um serviço do Coolify vêm do Docker Compose, e a tela não deixa criar File Mount. A skill vai
direto no volume `/data`, pelo script, que roda em Linux, macOS ou WSL:

```bash
scripts/openclaw_instalar_skill.sh root@<ip-do-servidor> ~/.ssh/<chave>
```

O script acha o container do gateway, grava `deploy/openclaw/skills/mandalore-relatorios/SKILL.md` em
`/data/workspace/skills/mandalore-relatorios/`, reinicia o gateway e confere que a skill aparece como pronta e que
há `curl` no container. Com mais de um OpenClaw no mesmo servidor, o script lista os containers e pede o do
cliente como terceiro parâmetro (`... ~/.ssh/<chave> openclaw-<uuid>`). Saída esperada:

```
│ ✓ ready   │ 📦 mandalore-     │ Relatórios do bot de atendimento da clínica (Mandalore)...
curl: ok
```

Sem SSH, dá para fazer pelo **Terminal** do container `openclaw-<uuid>` no Coolify:

```bash
mkdir -p /data/workspace/skills/mandalore-relatorios
cat > /data/workspace/skills/mandalore-relatorios/SKILL.md
# cole o conteúdo, Enter, Ctrl+D; depois Restart do serviço
```

Teste a ligação com o Mandalore de dentro do gateway:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $MANDALORE_OPENCLAW_TOKEN" "$MANDALORE_URL/openclaw/relatorios/curadoria"
```

Deve responder `200`.

## Passo 6. Grupo do Telegram

1. Crie o grupo da clínica no Telegram com as pessoas que recebem os relatórios.
2. Adicione o bot do OpenClaw. Confira o usuário exato (com ou sem `_`): bots com nomes parecidos já causaram
   confusão.
3. Torne o bot **administrador**, sem nenhuma permissão marcada. Assim ele recebe as menções feitas no grupo.
4. **O Telegram transforma o grupo em supergrupo e o ID muda** (de `-5410137492` para `-1004474298538` na BioSaúde).
   Anote o ID **depois** deste passo. Para ver o ID atual, abra o grupo no
   [Telegram Web](https://web.telegram.org/a/) e copie o número do fim do endereço, ou procure `chatId=` no log
   do gateway após uma mensagem.
5. Descubra o ID de cada pessoa que vai falar com o bot. No Telegram Web, abra a conversa com a pessoa e copie o
   número positivo do fim do endereço. Também aparece no log do gateway quando a pessoa escreve no grupo:
   `Blocked telegram group message from <id>`.
6. No OpenClaw, preencha `TELEGRAM_GROUP_ALLOW_FROM` com esses IDs e faça **Restart**.

Regras do OpenClaw para grupos no Telegram:

| Configuração | Controla | Situação padrão do template |
|---|---|---|
| `groupPolicy` | Se há filtro de pessoas nos grupos | `allowlist`: só quem está em `groupAllowFrom` |
| `groupAllowFrom` (`TELEGRAM_GROUP_ALLOW_FROM`) | Quais pessoas podem falar com o bot nos grupos | Vazio: **ninguém** consegue |
| `groups` (no `openclaw.json`) | Em quais grupos o bot atua | Ausente: qualquer grupo, respondendo só quando mencionado |
| `dmPolicy` | Mensagens no privado | `pairing`: quem escreve recebe um código e precisa de aprovação com `openclaw pairing approve telegram <código>` |

Para prender o bot a um grupo só, acrescente em `channels.telegram` do `/data/.openclaw/openclaw.json`
`"groups": {"<id-do-grupo>": {"requireMention": true}}` e reinicie. O script de inicialização preserva esse campo.

Teste no grupo, escolhendo o bot na lista de sugestões ao digitar `@`:

```
@<bot> mande a conversão de ontem do Mandalore
```

A resposta tem de trazer os mesmos números do botão **Conversão do dia** na tela de curadoria.

## Passo 7. Agendamentos

No terminal do gateway (`openclaw-<uuid>`), com o ID do grupo **depois** de virar supergrupo:

```bash
GRUPO="-1004474298538"

openclaw cron add --name "Resumo da curadoria" --cron "0 8 * * *" --tz America/Sao_Paulo \
  --session isolated --announce --channel telegram --to "$GRUPO" --timeout-seconds 120 \
  --message "Use a skill mandalore-relatorios: busque o relatório curadoria e envie o campo texto exatamente como veio."

openclaw cron add --name "Conversão do dia" --cron "5 8 * * *" --tz America/Sao_Paulo \
  --session isolated --announce --channel telegram --to "$GRUPO" --timeout-seconds 120 \
  --message "Use a skill mandalore-relatorios: busque o relatório conversao e envie o campo texto exatamente como veio."

openclaw cron add --name "Relatório do gestor" --cron "0 8 * * 1" --tz America/Sao_Paulo \
  --session isolated --announce --channel telegram --to "$GRUPO" --timeout-seconds 120 \
  --message "Use a skill mandalore-relatorios: busque o relatório gestor e envie o campo texto exatamente como veio."

openclaw cron list
```

Opções conferidas com `openclaw cron add --help` na versão `2026.2.6`. O cron tem 5 campos (minuto, hora, dia,
mês, dia da semana). Os agendamentos ficam em `/data` e sobrevivem a restart.

Para testar sem esperar o horário: `openclaw cron run <id>` (o ID aparece no `cron list`). Histórico:
`openclaw cron runs`.

## Passo 8. Homologação

- [ ] Mandalore responde 401 sem token em `/openclaw/relatorios/curadoria`
- [ ] Gateway `openclaw-<uuid>` em Running, sem reinícios, com o modelo e o bot certos no log
- [ ] `openclaw skills list` mostra `mandalore-relatorios` como `✓ ready`
- [ ] `curl` de dentro do gateway devolve 200
- [ ] Menção no grupo devolve a conversão de ontem, igual à tela de curadoria
- [ ] Mensagem de uma pessoa fora de `TELEGRAM_GROUP_ALLOW_FROM` é ignorada (`Blocked telegram group message` no log)
- [ ] `openclaw cron run` de cada agendamento chega ao grupo
- [ ] Ficha do cliente preenchida e segredos no cofre

## Problemas conhecidos

Todos aconteceram na primeira implantação.

| Sintoma | Causa | Solução |
|---|---|---|
| Container do OpenClaw em "Restarting", centenas de reinícios; terminal do Coolify cai no navegador | Nenhuma chave de modelo: `ERROR: At least one AI provider API key env var is required` | Preencher `GEMINI_API_KEY` e fazer Redeploy |
| `HOME=/config`, pasta `Desktop`, sem comando `openclaw` | Terminal aberto no container `browser-<uuid>` | Escolher o container `openclaw-<uuid>` |
| "Service volume mounts are read-only here" ao criar File Mount | Volumes de serviço vêm do Docker Compose | Instalar a skill pelo script (passo 5) |
| Bot não responde e o log não mostra a mensagem | Mensagem mencionou outro bot de nome parecido, ou o bot não recebe mensagens do grupo | Escolher o bot pela sugestão do `@`; tornar o bot administrador |
| `Blocked telegram group message from <id> (groupPolicy: allowlist)` | A pessoa não está em `TELEGRAM_GROUP_ALLOW_FROM` (na primeira vez, ficaram os IDs de exemplo do guia) | Colocar o ID real e reiniciar |
| Agendamento não chega ao grupo depois de tornar o bot administrador | O grupo virou supergrupo e o ID mudou | Recriar os agendamentos com o ID novo (`-100...`) |
| "API provider returned a billing error" no grupo | Projeto sem faturamento; o modelo Pro não tem plano gratuito | Ativar faturamento e usar `google/gemini-flash-latest` |
| "Unknown model: models/gemini-3.8-flash" | Formato `models/...` (o do Mandalore) e modelo fora do catálogo da versão | `OPENCLAW_PRIMARY_MODEL=google/gemini-flash-latest`; ver o catálogo com `openclaw models list --all --provider google` |
| Título "BioSaÃºde" nos relatórios | Valor padrão com acento no `application.properties`, lido como ISO-8859-1 | Definir `ADMIN_NOME`; corrigido no código com `\u00fa` |
| Skill não aparece em `skills list` | Falta `MANDALORE_URL`, `MANDALORE_OPENCLAW_TOKEN` ou `curl` (a skill exige os três) | Conferir as variáveis e `command -v curl` no gateway |

## Operação

| Tarefa | Como |
|---|---|
| Atualizar a skill | Alterar `deploy/openclaw/skills/mandalore-relatorios/SKILL.md`, commit e rodar o script do passo 5 em cada cliente |
| Incluir ou tirar uma pessoa | Editar `TELEGRAM_GROUP_ALLOW_FROM` e Restart |
| Trocar o `OPENCLAW_TOKEN` | Gerar outro, atualizar Mandalore e OpenClaw, reiniciar os dois |
| Trocar o modelo | `OPENCLAW_PRIMARY_MODEL` e Restart; conferir o nome no catálogo da versão |
| Atualizar a versão do OpenClaw | Em homologação primeiro; refazer o passo 8 |
| Ver o que o bot recebeu | `docker logs --since 10m openclaw-<uuid>` ou `/tmp/openclaw/openclaw-AAAA-MM-DD.log` no gateway |
| Pausar os relatórios | `openclaw cron disable <id>` |
| OpenClaw fora do ar | Envio direto pelo Mandalore ([relatorios-openclaw.md](relatorios-openclaw.md#envio-direto-pelo-mandalore-sem-openclaw)) |

## Limitação com várias clínicas

Os relatórios somam a trilha da instalação inteira do Mandalore. Se uma instalação atender várias clínicas
(várias contas do Chatwoot), os números saem juntos, e a conversão pode juntar conversas de clínicas diferentes
com o mesmo número. Até a trilha guardar a conta do Chatwoot em cada evento, implante um Mandalore e um OpenClaw
por cliente quando o cliente quiser relatórios.

## Registro de instalações

| Cliente | Data | Versão OpenClaw | Modelo | Bot | Grupo | Observações |
|---|---|---|---|---|---|---|
| BioSaúde | 06/11/2026 | `2026.2.6` | `google/gemini-flash-latest` | `@joclow_bot` | `-1004474298538` | Primeira implantação; agendamentos pendentes |
