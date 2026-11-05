# Deploy na Hostinger com Coolify

Um VPS da Hostinger roda o Coolify. O Coolify sobe o Chatwoot pelo template dele e o Mandalore pelo `Dockerfile` deste repositório, cuida do HTTPS (Traefik com Let's Encrypt) e faz backup dos bancos. O Oracle do Tasy fica na clínica, com a porta liberada só para o IP do VPS.

| Endereço | Serviço |
|---|---|
| `painel.seudominio.com.br` | Painel do Coolify |
| `chat.seudominio.com.br` | Chatwoot |
| `api.seudominio.com.br` | Mandalore |

## 1. Requisitos

| Item | Valor |
|---|---|
| VPS | Hostinger KVM 2: 2 vCPU, 8 GB, cerca de 100 GB NVMe. Com três ou mais clínicas, KVM 4 |
| Datacenter | Brasil (São Paulo) |
| Sistema | Ubuntu 24.04 LTS |
| Domínio | Registros A de `painel`, `chat` e `api` para o IP do VPS |
| Repositório Git | GitHub ou GitLab, privado, com este projeto |
| Armazenamento S3 | Para backup dos bancos (Cloudflare R2, Backblaze ou equivalente) |
| SMTP | Para convites e senhas do Chatwoot. Pode ser o e-mail da Hostinger |

| Processo | RAM aproximada |
|---|---|
| Coolify (painel, Traefik, banco próprio) | 0,5 a 1 GB |
| Chatwoot web e filas | 1,7 a 2,5 GB |
| PostgreSQL e Redis | 0,6 a 1,2 GB |
| Mandalore (`-Xmx1g`) | cerca de 1,5 GB |

## 2. Arquivos deste repositório

| Arquivo | Uso |
|---|---|
| `Dockerfile` | Build Maven com Java 21 e imagem final `eclipse-temurin:21-jre`, usuário sem root, healthcheck em `/actuator/health/liveness` |
| `.dockerignore` | Deixa fora da imagem segredos, testes, documentação e `target/` |
| `deploy/mandalore.env.example` | Variáveis de produção para colar no Coolify |
| `deploy/clientes.properties.example` | Credenciais por clínica, montado como arquivo no container |

O build da imagem não roda os testes. Rode `mvn test` antes de publicar.

## 3. Servidor

1. Contrate o VPS e anote o IP.
2. Ative backup e snapshots no painel da Hostinger.
3. Entre por SSH e atualize: `apt update && apt upgrade -y`.
4. Crie um usuário administrativo, use SSH só com chave e desligue o login por senha.
5. Instale o Coolify (se a Hostinger não oferecer o template pronto):

```bash
curl -fsSL https://cdn.coollabs.io/coolify/install.sh | sudo bash
```

6. Abra `http://<ip-do-vps>:8000`, crie o administrador e ative os dois fatores.
7. Em **Settings**, defina o domínio do painel `https://painel.seudominio.com.br`.
8. Firewall (painel da Hostinger e `ufw`): abertas 22, 80 e 443. Depois que o painel abrir pelo domínio, feche 8000, 6001 e 6002.

## 4. Chatwoot

1. No Coolify: **Projects → New → Resource → Service → Chatwoot**.
2. Domínio: `https://chat.seudominio.com.br`.
3. Variáveis a conferir:

| Variável | Valor |
|---|---|
| `FRONTEND_URL` | `https://chat.seudominio.com.br` |
| `SECRET_KEY_BASE` | Gerado pelo template, ou `openssl rand -hex 64` |
| `ENABLE_ACCOUNT_SIGNUP` | `false` |
| `SMTP_ADDRESS`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAILER_SENDER_EMAIL` | Dados do SMTP |
| `POSTGRES_PASSWORD`, `REDIS_PASSWORD` | Senhas fortes (o template costuma gerar) |

4. Fixe a versão da imagem do Chatwoot. Não use `latest`.
5. Faça o deploy, crie o super administrador e siga `chatwoot.md`: conta da clínica, caixa de entrada, bot.
6. A URL de saída do bot é `https://api.seudominio.com.br/chatwoot?token=<CHATWOOT_WEBHOOK_SECRET>`.

## 5. Banco da trilha do Mandalore

1. No Coolify: **New → Resource → Database → PostgreSQL 16**, nome `mandalore`.
2. Usuário `mandalore`, senha forte, banco `mandalore`.
3. Sem porta pública.
4. Anote o host interno que o Coolify mostra. Ele vai em `OBSERVABILITY_POSTGRES_JDBC_URL`.
5. Em **Backups**, agende um backup diário para o S3.

Faça o mesmo agendamento de backup no Postgres do Chatwoot.

## 6. Mandalore

1. No Coolify: **New → Resource → Application → Private Repository**, aponte para o repositório e a branch de produção.
2. **Build Pack:** Dockerfile.
3. **Ports Exposes:** `8080`.
4. **Domain:** `https://api.seudominio.com.br`.
5. **Health Check:** caminho `/actuator/health/liveness`, porta `8080`.
6. **Environment Variables:** cole `deploy/mandalore.env.example` preenchido. Marque como secretas as senhas, tokens e `DIALOGFLOW_CREDENTIALS` (o JSON inteiro da conta Google, em uma linha).
7. **Storages → File Mount:** destino `/app/config/clientes.properties`, conteúdo a partir de `deploy/clientes.properties.example`. Só é necessário com mais de uma clínica ou com credenciais por clínica. Outro File Mount em `/app/config/faq.yaml`, a partir de `deploy/faq.exemplo.yaml`, traz os textos das [perguntas frequentes](perguntas-frequentes.md).
8. **Resource limits:** memória de 1,5 GB para o container, compatível com `-Xmx1g`.
9. Deploy. Ative o deploy automático por push, se quiser.

Confira:

```bash
curl https://api.seudominio.com.br/actuator/health/liveness
```

Deve responder `{"status":"UP"}`. O `/actuator/health/readiness` só fica UP com o Oracle e a trilha alcançáveis.

## 7. Oracle do Tasy na clínica

O Mandalore acessa o Oracle pela internet. A clínica precisa:

| Item | Requisito |
|---|---|
| Firewall | Porta do listener (em geral 1521) liberada **só para o IP do VPS** |
| Endereço | IP público fixo ou DNS dinâmico, usado em `ORACLE_JDBC_URL` |
| Criptografia | `sqlnet.ora` com `SQLNET.ENCRYPTION_SERVER = REQUIRED`, `SQLNET.ENCRYPTION_TYPES_SERVER = (AES256)`, `SQLNET.CRYPTO_CHECKSUM_SERVER = REQUIRED`, `SQLNET.CRYPTO_CHECKSUM_TYPES_SERVER = (SHA1)`. O Mandalore recusa conexão sem criptografia |
| Usuário | Exclusivo do Mandalore, com grants mínimos (`especificacao-implantacao.md`, seção 5.1), senha forte, bloqueio após tentativas falhas, auditoria de login |
| Philips | Informar o acesso externo, se o contrato do Tasy exigir |

Se a clínica não aceitar expor a porta, a alternativa é uma VPN WireGuard entre o VPS e a clínica.

## 8. Integrações

| Serviço | Configuração |
|---|---|
| Dialogflow | Fulfillment em `https://api.seudominio.com.br/webhook` com Basic Auth (`DIALOGFLOW_WEBHOOK_USER` e `DIALOGFLOW_WEBHOOK_PASSWORD`) |
| Chatwoot | Bot com a URL de saída do passo 4 |
| Twilio (se o WhatsApp não entrar pelo Chatwoot) | Webhook `https://api.seudominio.com.br/twilio/whatsapp` e status `.../twilio/whatsapp/status`, iguais a `TWILIO_WEBHOOK_URL` e `TWILIO_STATUS_CALLBACK_URL` |
| Instagram | Modelo de `instagram-para-whatsapp.md` |

## 9. Homologação

1. Widget do site: conversa até o agendamento.
2. WhatsApp: a mesma conversa.
3. Agendar, confirmar, cancelar e remarcar contra o Tasy de homologação, com `AGENDAMENTO_ESCRITA=tasy`.
4. Pedir atendente: a conversa abre para a recepção no Chatwoot.
5. Instagram: link da bio e resposta automática levam ao WhatsApp.
6. Log do Mandalore no Coolify: `status=sucesso`, `canal=` correto, sem falha de Oracle.

## 10. Produção

1. Apontar `ORACLE_JDBC_URL` para o Tasy de produção.
2. `AGENDAMENTO_ESCRITA=tasy`.
3. Monitor externo (UptimeRobot ou similar) em `https://api.seudominio.com.br/actuator/health/liveness` e em `https://chat.seudominio.com.br`.
4. Conferir que os backups diários dos dois Postgres chegam ao S3.
5. Registrar acessos e senhas num cofre de senhas.

## Operação

| Tarefa | Como |
|---|---|
| Atualizar o Mandalore | Push na branch de produção (deploy automático) ou **Redeploy** no Coolify |
| Voltar versão | **Deployments** → versão anterior → **Redeploy** |
| Atualizar o Chatwoot | Trocar a versão fixada da imagem, com snapshot do VPS antes |
| Atualizar o Coolify | Pelo painel, com snapshot do VPS antes |
| Ver logs | Recurso → **Logs** |
| Nova clínica | Conta no Chatwoot, entrada em `clientes.properties`, liberação do Oracle da nova clínica para o mesmo IP |
