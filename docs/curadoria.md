# Curadoria do agente

Cada mensagem enviada ao Dialogflow gera um evento `DIALOGFLOW / detect_intent` em
`observability.flow_event` (requer `OBSERVABILITY_POSTGRES_ENABLED=true`). Em `details`:

| Campo | Conteúdo |
|---|---|
| `texto` | Fala do paciente com `<cpf>`, `<data>`, `<telefone>`, `<email>` e `<numero>` (5+ dígitos) no lugar dos dados |
| `confianca` | `intentDetectionConfidence` do Dialogflow (0 a 1) |
| `fallback` | `true` quando caiu numa intent de fallback |

Nomes próprios não são mascarados: o texto continua sendo dado pessoal de saúde (LGPD).
Restrinja o acesso ao banco e apague o histórico antigo (abaixo). Para não guardar a fala,
use `OBSERVABILITY_CURADORIA_TEXTO=false`.

## Rotina semanal

1. Revisar a fila de não entendidas e de baixa confiança.
2. Para cada frase: incluir como frase de treino na intent certa, criar intent nova ou marcar como fora do escopo.
3. Virar as correções em casos de [regressão conversacional](testes-regressao-conversacional.md), rodar e publicar o agente.

## Consultas

Fila de curadoria (últimos 7 dias, frases repetidas agrupadas):

```sql
SELECT lower(details->>'texto') AS texto,
       count(*) AS vezes,
       max(intent) AS intent,
       round(avg((details->>'confianca')::numeric), 2) AS confianca,
       bool_or((details->>'fallback')::boolean) AS fallback,
       max(created_at) AS ultima
FROM observability.flow_event
WHERE component = 'DIALOGFLOW' AND stage = 'detect_intent'
  AND created_at > now() - interval '7 days'
  AND ((details->>'fallback')::boolean OR (details->>'confianca')::numeric < 0.6)
GROUP BY 1
ORDER BY vezes DESC, ultima DESC
LIMIT 100;
```

Indicadores por dia:

```sql
SELECT date_trunc('day', created_at)::date AS dia,
       count(*) AS mensagens,
       round(100.0 * count(*) FILTER (WHERE (details->>'fallback')::boolean) / count(*), 1) AS pct_fallback,
       round(100.0 * count(*) FILTER (WHERE (details->>'confianca')::numeric < 0.6) / count(*), 1) AS pct_baixa_confianca,
       count(DISTINCT conversation_id) AS conversas,
       count(DISTINCT conversation_id) FILTER (WHERE intent LIKE 'transbordo.%') AS transbordos
FROM observability.flow_event
WHERE component = 'DIALOGFLOW' AND stage = 'detect_intent'
  AND created_at > now() - interval '30 days'
GROUP BY 1
ORDER BY 1 DESC;
```

Intents mais acionadas e confiança média:

```sql
SELECT intent, count(*) AS vezes, round(avg((details->>'confianca')::numeric), 2) AS confianca
FROM observability.flow_event
WHERE component = 'DIALOGFLOW' AND stage = 'detect_intent'
  AND created_at > now() - interval '30 days'
GROUP BY intent
ORDER BY vezes DESC;
```

Retenção (ex.: 90 dias):

```sql
DELETE FROM observability.flow_event WHERE created_at < now() - interval '90 days';
```
