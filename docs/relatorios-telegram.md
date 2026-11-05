# Relatórios no Telegram

O Mandalore manda dois relatórios a um grupo do Telegram, com números da trilha operacional:

| Relatório | Quando | Período |
|---|---|---|
| Resumo da curadoria | Todo dia, 8h | Ontem, comparado com os 7 dias anteriores |
| Relatório do gestor | Segunda, 8h | Semana anterior (segunda a domingo), comparada com a semana antes dela |

Os relatórios trazem só contagens. Fala de paciente, nome e CPF não saem da central: as falas da fila continuam
na tela de [curadoria](curadoria.md).

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

Revisar a fila: https://bs-api.jscinfor.com/curadoria
```

```
Relatório semanal do bot - BioSaúde
26/10 a 01/11/2026. Entre parênteses, a semana anterior.

Conversas: 120 (100)
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

Gravações, transbordos e orçamentos passaram a entrar na trilha com esta versão. A primeira semana após o deploy
mostra zero na coluna da semana anterior.

## Configurar

### 1. Bot e grupo no Telegram

1. No Telegram, fale com o **@BotFather**, envie `/newbot` e siga as perguntas. Guarde o **token**.
2. Crie um grupo com quem recebe os relatórios e adicione o bot.
3. Mande qualquer mensagem no grupo.
4. Abra `https://api.telegram.org/bot<TOKEN>/getUpdates` no navegador e copie o `chat.id` do grupo
   (número negativo, como `-1001234567890`).

O token dá controle do bot. Guarde só no Coolify, como variável secreta.

### 2. Coolify

```
RELATORIOS_ENABLED=true
TELEGRAM_BOT_TOKEN=<token do BotFather>
TELEGRAM_CHAT_ID=<chat.id do grupo>
RELATORIOS_URL=https://bs-api.jscinfor.com
```

Opcionais:

| Variável | Padrão | Uso |
|---|---|---|
| `RELATORIO_CURADORIA_CRON` | `0 0 8 * * *` | Horário do resumo (cron do Spring: segundo, minuto, hora, dia, mês, dia da semana). `-` desliga |
| `RELATORIO_GESTOR_CRON` | `0 0 8 * * MON` | Horário do relatório semanal. `-` desliga |
| `RELATORIOS_URL` | vazio | Endereço público do Mandalore. Vazio: o resumo sai sem o link da curadoria |
| `ADMIN_NOME` | `BioSaúde` | Nome no título dos relatórios |

O horário é sempre de São Paulo. Requer `OBSERVABILITY_POSTGRES_ENABLED=true`. Com mais de uma réplica do
Mandalore, cada réplica envia o seu: deixe os relatórios ligados em uma só.

## Testar

Na tela de curadoria, os botões **Resumo no Telegram** e **Semanal no Telegram** enviam na hora.

Para ver o texto sem enviar, com o login da central:

```bash
curl -u admin https://bs-api.jscinfor.com/curadoria/api/relatorios/curadoria
curl -u admin https://bs-api.jscinfor.com/curadoria/api/relatorios/gestor
```

Para enviar pela linha de comando, use `-X POST` no mesmo endereço.

| Log ou aviso | Significado |
|---|---|
| `relatorio tipo=CURADORIA status=enviado` | Chegou ao Telegram |
| `Defina TELEGRAM_BOT_TOKEN e TELEGRAM_CHAT_ID` | Falta uma das variáveis |
| `HTTP 400 ... chat not found` | `TELEGRAM_CHAT_ID` errado, ou o bot não está no grupo |
| `HTTP 401` | Token inválido |
| "Relatórios desligados" na tela | `RELATORIOS_ENABLED` não está `true` |
