# Canal WhatsApp com Twilio

O Mandalore recebe mensagens pelo webhook da Twilio, consulta o Dialogflow e
envia a resposta pelo SDK oficial `twilio-java`.

## Configuração

```env
TWILIO_ENABLED=true
TWILIO_ACCOUNT_SID=AC...
TWILIO_AUTH_TOKEN=...
TWILIO_WHATSAPP_FROM=whatsapp:+14155238886
TWILIO_WEBHOOK_URL=https://seu-dominio/twilio/whatsapp
TWILIO_STATUS_CALLBACK_URL=https://seu-dominio/twilio/whatsapp/status
```

As URLs devem ser exatamente as URLs públicas configuradas na Twilio. Elas
participam do cálculo de `X-Twilio-Signature`; diferenças entre HTTP/HTTPS,
host, porta, caminho ou query string invalidam a assinatura.

## Sandbox

1. Ative o WhatsApp Sandbox no Console da Twilio.
2. Envie `join <código>` pelo número que fará o teste.
3. Em **When a message comes in**, configure:
   - URL: valor de `TWILIO_WEBHOOK_URL`;
   - método: `POST`.
4. Use `whatsapp:+14155238886` em `TWILIO_WHATSAPP_FROM`.

As respostas do chatbot usam `Body`, sem `ContentSid`, dentro da janela de
atendimento iniciada pela mensagem recebida.

## Número oficial

Depois que o sender oficial estiver aprovado, altere apenas:

```env
TWILIO_WHATSAPP_FROM=whatsapp:+55...
```

Atualize também as URLs públicas se o domínio mudar. Não é necessário alterar
o código.

Mensagens proativas fora da janela de atendimento exigem um template aprovado
e um `ContentSid` pertencente à mesma conta. Esse fluxo deve ficar separado das
respostas conversacionais.

## Segurança e operação

- Credenciais são lidas somente do ambiente.
- Todo webhook é validado com `X-Twilio-Signature`.
- Todos os parâmetros recebidos participam da validação.
- `MessageSid` evita reprocessamento de retries na mesma instância.
- O callback registra `MessageStatus` e `ErrorCode`.
- Nunca registre Auth Token, conteúdo clínico ou telefone completo em logs.

Para múltiplas réplicas, substitua a idempotência em memória por Redis ou uma
tabela compartilhada com expiração.

Documentação oficial:

- https://www.twilio.com/docs/whatsapp/sandbox
- https://www.twilio.com/docs/usage/webhooks/webhooks-security
- https://www.twilio.com/docs/messaging/guides/track-outbound-message-status
