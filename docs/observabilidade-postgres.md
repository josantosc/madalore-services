# Observabilidade no PostgreSQL

O Mandalore grava uma trilha operacional append-only em
`observability.flow_event`. A gravação é best-effort: indisponibilidade da
telemetria não interrompe a conversa nem as operações de agenda.

## Cobertura

- Twilio: recebimento, assinatura, envio e callbacks de entrega;
- Dialogflow: `detectIntent`, intent, action, webhook de fulfillment e status;
- Mandalore: processamento, evento de continuidade e resultado de negócio;
- Tasy/PostgreSQL: falhas de consulta ou escrita;
- aplicação: inicialização e encerramento.

Não são persistidos telefone, CPF, corpo da mensagem, token ou conteúdo
clínico. `message_sid`, `conversation_id` anonimizado e `correlation_id` são
usados apenas para correlação operacional.

## Configuração

No perfil `local-postgres`, a observabilidade é habilitada por padrão no banco
local. Para outro ambiente:

```env
OBSERVABILITY_POSTGRES_ENABLED=true
OBSERVABILITY_POSTGRES_JDBC_URL=jdbc:postgresql://servidor:5432/banco
OBSERVABILITY_POSTGRES_USER=usuario
OBSERVABILITY_POSTGRES_PASSWORD=senha
```

Antes de habilitar fora do perfil local, aplique:

```text
src/main/resources/db/observability/schema.sql
```

## Consultas

Timeline completa de uma conversa:

```sql
SELECT created_at, component, stage, status, intent, action, event_name,
       business_result, message_sid, delivery_status, http_status,
       error_code, error_type, error_message, duration_ms
FROM observability.flow_event
WHERE conversation_id = :conversation_id
ORDER BY created_at, id;
```

Timeline por SID da Twilio:

```sql
WITH fluxo AS (
    SELECT conversation_id
    FROM observability.flow_event
    WHERE message_sid = :message_sid
    ORDER BY created_at DESC
    LIMIT 1
)
SELECT e.*
FROM observability.flow_event e
JOIN fluxo f USING (conversation_id)
ORDER BY e.created_at, e.id;
```

Erros recentes por origem:

```sql
SELECT component, stage, error_code, error_type, count(*) AS quantidade
FROM observability.flow_event
WHERE status = 'ERROR'
  AND created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
GROUP BY component, stage, error_code, error_type
ORDER BY quantidade DESC;
```

Falhas de entrega da Twilio:

```sql
SELECT created_at, message_sid, delivery_status, error_code, error_message
FROM observability.flow_event
WHERE component = 'TWILIO'
  AND stage = 'delivery.callback'
  AND status = 'ERROR'
ORDER BY created_at DESC;
```

Falhas de intent/action no Dialogflow:

```sql
SELECT created_at, intent, action, stage, error_code, error_message
FROM observability.flow_event
WHERE component = 'DIALOGFLOW'
  AND status = 'ERROR'
ORDER BY created_at DESC;
```

## Retenção

Para ambiente de teste, recomenda-se reter 30 dias:

```sql
DELETE FROM observability.flow_event
WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '30 days';
```

Em produção, execute a limpeza por job agendado e limite o acesso ao schema
`observability` à equipe de operação.
