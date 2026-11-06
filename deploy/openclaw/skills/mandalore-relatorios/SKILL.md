---
name: mandalore-relatorios
description: Relatórios do bot de atendimento da clínica (Mandalore). Use para o resumo diário da curadoria, a conversão diária, o relatório semanal do gestor, as pendências da agenda da recepção e perguntas sobre números do bot, como conversas, conversão, consultas e exames marcados, transbordos por equipe, orçamentos e perguntas frequentes.
metadata: {"openclaw": {"requires": {"env": ["MANDALORE_URL", "MANDALORE_OPENCLAW_TOKEN"], "bins": ["curl"]}, "primaryEnv": "MANDALORE_OPENCLAW_TOKEN"}}
---

# Relatórios do Mandalore

O Mandalore calcula os números do bot da clínica. Esta skill só lê: não há como alterar nada no Mandalore por aqui.
Os números não têm nome, CPF nem fala de paciente.

## Relatórios prontos

```bash
curl -s -H "Authorization: Bearer $MANDALORE_OPENCLAW_TOKEN" "$MANDALORE_URL/openclaw/relatorios/curadoria"
curl -s -H "Authorization: Bearer $MANDALORE_OPENCLAW_TOKEN" "$MANDALORE_URL/openclaw/relatorios/conversao"
curl -s -H "Authorization: Bearer $MANDALORE_OPENCLAW_TOKEN" "$MANDALORE_URL/openclaw/relatorios/gestor"
curl -s -H "Authorization: Bearer $MANDALORE_OPENCLAW_TOKEN" "$MANDALORE_URL/openclaw/relatorios/agenda"
```

Resposta: `{"tipo": "curadoria", "texto": "..."}`.

- `curadoria`: dia de ontem, comparado com os 7 dias anteriores.
- `conversao`: das conversas de ontem, quantas terminaram com consulta ou exame marcado pelo bot, quantas foram
  para atendente sem marcar e quantas ficaram sem nenhum dos dois, comparado com os 7 dias anteriores.
- `gestor`: semana anterior, de segunda a domingo, comparada com a semana antes dela, incluindo a taxa de conversão.
- `agenda`: só para clínicas que usam a agenda do Mandalore. Pendências que a recepção ainda precisa lançar no
  sistema da clínica, quantas estão atrasadas, divergências da conferência e consultas de amanhã sem conferência.
  Use quando perguntarem se as duas agendas estão em dia ou se há algo atrasado para a recepção.

Ao enviar um relatório, mande o campo `texto` exatamente como veio. Não arredonde, não reescreva números e não
acrescente conclusões dentro do texto. Se quiser comentar, faça numa mensagem separada e deixe claro que é análise.

## Perguntas avulsas

```bash
curl -s -H "Authorization: Bearer $MANDALORE_OPENCLAW_TOKEN" "$MANDALORE_URL/openclaw/numeros?de=2026-11-02&ate=2026-11-08"
```

`de` e `ate` em AAAA-MM-DD, os dois dias inclusive, horário de São Paulo. Sem `ate`, vale só o dia `de`.
Intervalo máximo de 92 dias. Resposta:

```json
{"de": "2026-11-02", "ate": "2026-11-08", "numeros": {
  "mensagens": 900, "conversas": 120, "naoEntendidas": 45, "baixaConfianca": 60,
  "pendentes": 12, "ensinadas": 3, "faqRespondidas": 30,
  "faqSemResposta": {"estacionamento": 5},
  "escritas": {"consulta": 40, "exame": 15, "confirmar": 8, "cancelar": 4, "remarcar": 3},
  "transbordos": {"RECEPCAO": 10, "LABORATORIO": 6},
  "orcamentos": 7,
  "falhas": {"TASY": 2},
  "conversao": {"conversas": 118, "convertidas": 50, "atendente": 14}
}}
```

| Campo | Significado |
|---|---|
| `mensagens`, `conversas` | Mensagens de pacientes ao bot e conversas distintas |
| `naoEntendidas`, `baixaConfianca` | Mensagens que o bot não entendeu, ou entendeu com pouca certeza |
| `pendentes` | Falas na fila de curadoria nos 7 dias antes do fim do período |
| `ensinadas` | Frases ensinadas ao bot pela equipe na curadoria |
| `faqRespondidas`, `faqSemResposta` | Perguntas frequentes respondidas; temas perguntados que ainda não têm texto |
| `escritas` | Consultas e exames marcados, confirmações, cancelamentos e remarcações |
| `transbordos` | Conversas passadas para atendente, por equipe (Recepção, Laboratório, Faturamento) |
| `orcamentos` | Pedidos de orçamento por foto |
| `falhas` | Erros registrados, por sistema |
| `conversao.conversas` | Conversas com mensagem no período (base da conversão) |
| `conversao.convertidas` | Dessas, quantas tiveram consulta ou exame marcado pelo bot |
| `conversao.atendente` | Quantas foram para atendente sem o bot marcar |

Taxa de conversão = `convertidas / conversas`. Sem desfecho = `conversas - convertidas - atendente`. A conversão
conta conversas, não pessoas, e só o que o bot marcou: agendamentos feitos pela equipe depois do transbordo não
entram. Diga isso quando alguém perguntar pela conversão.

Equipe ou tema ausente no mapa vale zero. Para percentuais de mensagens, divida pelo total de `mensagens` do mesmo período.
Para comparar períodos, faça uma chamada por período.

## Erros

| HTTP | O que dizer |
|---|---|
| 401 | Token recusado. Avise quem administra o OpenClaw; não tente outro token |
| 503 | O Mandalore está sem `OPENCLAW_TOKEN` configurado |
| 404 | Relatórios desligados no Mandalore (`RELATORIOS_ENABLED`) |
| 400 | Data inválida ou intervalo maior que 92 dias: corrija a pergunta |

Se a chamada falhar, diga que não conseguiu buscar os números. Nunca invente ou estime um número.
