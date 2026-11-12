# Relatórios pelo OpenClaw

O Mandalore calcula dois relatórios com números da trilha operacional. O OpenClaw lê esses relatórios por uma API
só de leitura, agenda o envio e manda ao grupo do Telegram. No mesmo grupo, a equipe pode fazer perguntas avulsas
("quantos transbordos para o laboratório na terça?"), que o OpenClaw responde com a mesma API.

| Relatório | Quando (agendado no OpenClaw) | Período |
|---|---|---|
| Resumo da curadoria | Todo dia, 8h | Ontem, comparado com os 7 dias anteriores |
| Conversão | Todo dia, 8h | Ontem, comparado com os 7 dias anteriores |
| Relatório do gestor | Segunda, 8h | Semana anterior (segunda a domingo), comparada com a semana antes dela |

Os relatórios trazem só contagens. Fala de paciente, nome e CPF não saem da central: as falas da fila continuam
na tela de [curadoria](curadoria.md). O OpenClaw não recebe credencial do Tasy, do Chatwoot, do Dialogflow nem do
PostgreSQL: só o token da API de relatórios.

## Divisão de papéis

| Mandalore | OpenClaw |
|---|---|
| Lê a trilha no PostgreSQL e monta o texto dos relatórios | Agenda os resumos diários e o semanal |
| Expõe `GET /openclaw/relatorios/{curadoria,conversao,gestor}` e `GET /openclaw/numeros` | Envia o texto ao grupo do Telegram, sem alterar números |
| Confere o token `OPENCLAW_TOKEN` | Responde perguntas avulsas no grupo com `/openclaw/numeros` |

## Exemplo

```
Curadoria do bot - BioSaúde
Ontem, 04/11/2026

Mensagens: 200 em 50 conversas
Não entendidas: 10 (5,0%). Média dos 7 dias anteriores: 8,0%
Confiança baixa: 30 (15,0%)
Perguntas frequentes respondidas: 40
Na fila de curadoria (7 dias): 12
Frases ensinadas: 3
Perguntas frequentes sem texto: estacionamento 5, contato 2
Falhas registradas: TASY 2

Revisar a fila: https://api.biosaudelac.com/curadoria
```

```
Conversão do bot - BioSaúde
Ontem, 04/11/2026

Conversas: 50
Marcaram consulta ou exame: 12 (24,0%). Média dos 7 dias anteriores: 20,0%
Passaram para atendente sem marcar: 8 (16,0%)
Sem agendamento nem atendente: 30 (60,0%)

Conta só o que o bot marcou. Agendamentos feitos pela equipe depois do transbordo não entram.
```

```
Relatório semanal do bot - BioSaúde
26/10 a 01/11/2026. Entre parênteses, a semana anterior.

Conversas: 120 (100)
Conversão (conversas com consulta ou exame marcado pelo bot): 25,0% (20,0%)
Consultas marcadas: 40 (35)
Exames marcados: 15 (12)
Confirmações: 8 (6)
Cancelamentos: 4 (5)
Remarcações: 3 (2)
Passadas para atendente: 16 (9)
  Recepção 10, Laboratório 6
Pedidos de orçamento por foto: 7 (5)
Perguntas frequentes respondidas: 30 (20)
Mensagens não entendidas: 5,0% (7,0%)
```

| Linha | Origem na trilha (`observability.flow_event`) |
|---|---|
| Mensagens, conversas, não entendidas, confiança baixa | `DIALOGFLOW / detect_intent` |
| Consultas, exames, confirmações, cancelamentos, remarcações | `MANDALORE / fulfillment.*` com evento `RESULTADO_SUCESSO_ESCRITA` (`details.escrita`, `details.exame`) |
| Passadas para atendente, por equipe | `CHATWOOT / transbordo` (`details.equipe`) |
| Pedidos de orçamento por foto | `CHATWOOT / orcamento.pedido` |
| Perguntas frequentes | `MANDALORE / fulfillment.<tema>` das intents `acao.faq.*` |
| Na fila de curadoria, frases ensinadas | Mesma regra da tela de curadoria |
| Falhas registradas | Eventos com `status = 'ERROR'`, por componente |
| Conversão | Conversas com `DIALOGFLOW / detect_intent` no período; convertidas são as que têm `RESULTADO_SUCESSO_ESCRITA` com `escrita = agendar` no período; atendente, as que têm `CHATWOOT / transbordo` sem agendamento |

### Como ler a conversão

- A base é a **conversa** no canal (número da conversa no Chatwoot), não a pessoa. Quem abre duas conversas conta duas vezes.
- Só entra o que o **bot** marcou. Depois do transbordo, a equipe marca direto no Tasy e isso não chega à trilha.
  Por isso a conversão real da clínica é maior que a mostrada; "passaram para atendente sem marcar" é o teto do
  que a equipe pode ter convertido.
- Agendamento e mensagem precisam cair no mesmo período. Uma conversa que começa às 23h50 e marca à 0h10 conta
  a mensagem num dia e o agendamento no outro.
- Com várias clínicas, o número da conversa do Chatwoot é por conta: duas clínicas podem ter a mesma conversa 120
  no mesmo dia e contar como uma. Com uma clínica por instalação, não acontece.

Gravações, transbordos e orçamentos passaram a entrar na trilha a partir de 05/11/2026. A primeira semana após
esse deploy mostra zero na coluna da semana anterior.

## API para o OpenClaw

Todas as chamadas levam `Authorization: Bearer <OPENCLAW_TOKEN>`.

| Chamada | Resposta |
|---|---|
| `GET /openclaw/relatorios/curadoria` | `{"tipo": "curadoria", "texto": "..."}` |
| `GET /openclaw/relatorios/conversao` | `{"tipo": "conversao", "texto": "..."}` |
| `GET /openclaw/relatorios/gestor` | `{"tipo": "gestor", "texto": "..."}` |
| `GET /openclaw/numeros?de=AAAA-MM-DD&ate=AAAA-MM-DD` | `{"de", "ate", "numeros": {...}}`, dias inclusive, até 92 dias. Sem `ate`, só o dia `de` |

| HTTP | Significado |
|---|---|
| 401 | Token ausente ou errado. Gera `openclaw acesso recusado` no log |
| 503 | `OPENCLAW_TOKEN` vazio no Mandalore |
| 404 | `RELATORIOS_ENABLED` ou `OBSERVABILITY_POSTGRES_ENABLED` não está `true` |
| 400 | Tipo, data ou intervalo inválido |

Os campos de `numeros` estão descritos na skill
[`deploy/openclaw/skills/mandalore-relatorios/SKILL.md`](../deploy/openclaw/skills/mandalore-relatorios/SKILL.md).

## Configurar

O passo a passo para um cliente novo, com a ficha do cliente, os problemas conhecidos e a homologação, está em
[Implantação do OpenClaw por cliente](openclaw-implantacao-cliente.md). Em resumo:

| Onde | O quê |
|---|---|
| Mandalore | `RELATORIOS_ENABLED=true`, `OPENCLAW_TOKEN` (secret), `RELATORIOS_URL`, `ADMIN_NOME`; requer `OBSERVABILITY_POSTGRES_ENABLED=true` |
| OpenClaw | `GEMINI_API_KEY`, `OPENCLAW_PRIMARY_MODEL=google/gemini-flash-latest`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_GROUP_ALLOW_FROM`, `MANDALORE_URL`, `MANDALORE_OPENCLAW_TOKEN` (o mesmo `OPENCLAW_TOKEN`) |
| Skill | `scripts/openclaw_instalar_skill.sh root@<servidor> <chave>` grava a skill no volume do gateway |
| Grupo | Bot do OpenClaw como administrador; o ID do grupo muda para `-100...` nesse momento |
| Agendamentos | `openclaw cron add ... --announce --channel telegram --to "<id do grupo>"`, um por relatório |

`RELATORIOS_URL` coloca o link da curadoria no resumo; vazio, o resumo sai sem link. Se os dois containers
estiverem na mesma rede do Coolify, `MANDALORE_URL` pode ser o endereço interno do Mandalore
(`http://<nome-do-servico>:8080`), sem passar pela internet.

## Testar

Na tela de curadoria, os botões **Resumo do dia**, **Conversão do dia** e **Relatório semanal** mostram o texto
que o OpenClaw recebe. O card **Conversão** acompanha o período escolhido na tela (hoje, 7, 30 ou 90 dias); passe
o mouse sobre ele para ver quantas conversas foram para atendente e quantas ficaram sem desfecho.

A API, com o token (PowerShell):

```powershell
$h = @{ Authorization = "Bearer $env:OPENCLAW_TOKEN" }
(Invoke-RestMethod "https://api.biosaudelac.com/openclaw/relatorios/curadoria" -Headers $h).texto
(Invoke-RestMethod "https://api.biosaudelac.com/openclaw/numeros?de=2026-11-02&ate=2026-11-08" -Headers $h).numeros
```

No grupo, peça ao OpenClaw "mande o resumo da curadoria do Mandalore" e compare com a prévia da tela de curadoria.
Os números precisam ser iguais.

## Envio direto pelo Mandalore, sem OpenClaw

Se o OpenClaw estiver fora do ar, o Mandalore pode enviar sozinho com o próprio bot:

```
RELATORIOS_TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=<token do bot do Mandalore>
TELEGRAM_CHAT_ID=<ID do grupo>
```

| Variável | Padrão | Uso |
|---|---|---|
| `RELATORIO_CURADORIA_CRON` | `0 0 8 * * *` | Horário do resumo (cron do Spring: segundo, minuto, hora, dia, mês, dia da semana). `-` desliga |
| `RELATORIO_GESTOR_CRON` | `0 0 8 * * MON` | Horário do relatório semanal. `-` desliga |

Use um bot diferente do bot do OpenClaw. Não ligue os dois ao mesmo tempo, ou o grupo recebe cada relatório
duas vezes. Com mais de uma réplica do Mandalore, cada réplica envia o seu: deixe o envio ligado em uma só. Para
enviar na hora: `curl -u admin -X POST https://api.biosaudelac.com/curadoria/api/relatorios/curadoria`.

| Log | Significado |
|---|---|
| `relatorio tipo=CURADORIA status=enviado` | Chegou ao Telegram |
| `Defina TELEGRAM_BOT_TOKEN e TELEGRAM_CHAT_ID` | Falta uma das variáveis |
| `HTTP 400 ... chat not found` | `TELEGRAM_CHAT_ID` errado, ou o bot não está no grupo |
| `HTTP 401` | Token do bot inválido |
