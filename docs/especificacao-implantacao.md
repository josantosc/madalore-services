# ANEXO II — Especificação Técnica de Implantação — Mandalore

Anexo técnico do contrato de licenciamento e implantação do Mandalore. Define o que a CONTRATANTE provê, o que a solução lê e grava no Tasy e os limites impostos pelo WhatsApp, pela Twilio e pelo Dialogflow. Em caso de divergência com as Condições Gerais, prevalece o contrato.

| Campo | Valor |
|---|---|
| Produto | Mandalore — assistente de agendamento por WhatsApp |
| Versão de referência | 0.1.0 |
| Data | 16/10/2026 |
| Idioma da conversa | pt-BR |
| Fuso | America/Sao_Paulo |

Este anexo não contém senha, token nem URL de homologação. Credenciais são trocadas por canal seguro, fora do documento.

## 1. Descrição da solução

O Mandalore conversa com o paciente pelo WhatsApp, interpreta a mensagem no Google Dialogflow ES e consulta e grava a agenda diretamente no banco Oracle do Tasy.

Fluxo de uma mensagem:

1. O paciente envia texto pelo WhatsApp para o número oficial do hospital.
2. A Twilio chama `POST /twilio/whatsapp` do Mandalore em HTTPS.
3. O Mandalore valida a assinatura da Twilio e envia o texto ao Dialogflow.
4. Quando a intenção exige dado do hospital, o Dialogflow chama `POST /webhook` do Mandalore.
5. O Mandalore lê o Tasy e, na marcação, confirmação, cancelamento ou remarcação, grava no Tasy.
6. O Mandalore devolve a resposta pela API da Twilio, dentro da janela aberta pela mensagem do paciente.

Não há serviço intermediário entre o Mandalore e o Oracle do Tasy. A conexão é JDBC, com o usuário de serviço definido na seção 5.

## 2. Escopo funcional

| Capacidade | Leitura no Tasy | Gravação no Tasy |
|---|---|---|
| Identificar o paciente por CPF e data de nascimento | Sim | Não |
| Listar convênios, profissionais e especialidades ativos | Sim | Não |
| Oferecer até 8 horários livres de consulta | Sim | Não |
| Marcar consulta para paciente cadastrado | Sim | Sim, em `AGENDA_CONSULTA` |
| Marcar consulta para paciente sem cadastro (nominal) | Sim | Sim, em `AGENDA_CONSULTA`, sem criar `PESSOA_FISICA` |
| Listar consultas futuras do paciente | Sim | Não |
| Confirmar consulta | Sim | Sim, status `CN` |
| Cancelar consulta | Sim | Sim, devolve o horário para `L` |
| Remarcar consulta | Sim | Sim, reserva o novo e libera o anterior na mesma transação |
| Listar exames e horários de exame | Sim | Não |
| Marcar exame | Sim | Sim, em `AGENDA_PACIENTE` |
| Mostrar orientação de preparo do exame | Sim | Não |

Fora do escopo funcional (ver Anexo III):

- mensagem proativa, como lembrete, pesquisa ou confirmação ativa, que exige template aprovado pela Meta;
- áudio, imagem, documento e botões nativos do WhatsApp. A mensagem sem texto recebe um pedido para digitar a opção;
- atendimento humano no mesmo número de WhatsApp;
- criação ou alteração de cadastro em `PESSOA_FISICA`;
- regras de autorização de convênio, guias, faturamento e encaixe.

## 3. Responsabilidades técnicas

| Item | CONTRATANTE | CONTRATADA |
|---|---|---|
| Servidor de aplicação, sistema operacional, Java 21, proxy HTTPS e certificado | Provê e opera | Especifica, instala a aplicação e valida |
| Oracle do Tasy, usuário de serviço, grants e rota de rede | Provê | Especifica tabelas, colunas e grants |
| Validação com a Philips do uso de `UPDATE` direto nas tabelas de agenda | Responsável | Fornece este anexo e os comandos executados |
| Conta Twilio, pagamento e número WhatsApp | Titular e pagador | Configura webhooks e assinatura |
| Verificação da empresa na Meta, nome de exibição e WABA | Documentos e aprovação | Orienta e acompanha |
| Projeto Google Cloud e Dialogflow ES | Titular e pagador, quando a conta for da CONTRATANTE | Publica o agente e configura a conta de serviço |
| PostgreSQL da trilha operacional | Provê a instância | Fornece o script e configura |
| Opt-in do paciente e base legal (LGPD) | Controladora | Operadora |
| Conferência do dicionário Tasy da versão instalada | Responsável | Implementa os filtros da seção 7 |

## 4. Servidor de aplicação

A aplicação é um JAR Spring Boot 3.3, Java 21, porta HTTP `8080` (`HTTP_PORT`). O piloto roda em uma instância.

| Recurso | Mínimo | Recomendado |
|---|---|---|
| Sistema | Windows Server ou Linux x64 com NTP | Linux x64 |
| CPU | 2 vCPU | 4 vCPU |
| Memória | 4 GB, heap da JVM de 1 GB | 8 GB, heap de 2 GB |
| Disco | 20 GB | 40 GB, com rotação de log |
| Java | 21 (Temurin ou equivalente) | 21 |
| Fuso | America/Sao_Paulo | America/Sao_Paulo |

Os valores acima dimensionam um processo com pool Oracle de 5 conexões. Não resultam de teste de carga e não constituem capacidade contratada.

A aplicação sobe mesmo com o Oracle fora no instante da inicialização. O pool abre na primeira consulta. A falha da trilha operacional não interrompe a conversa.

### 4.1 Portas e processos

| Processo | Onde | Porta |
|---|---|---|
| Mandalore | Servidor de aplicação | 8080, só na rede interna |
| Proxy reverso (IIS, nginx ou equivalente) | Mesmo servidor ou borda | 443 público |
| Oracle Tasy | Servidor do Tasy | Listener do cliente, em geral 1521 |
| PostgreSQL da trilha | Instância própria, fora do Tasy | 5432 ou porta interna |
| Twilio, Dialogflow e OAuth Google | Internet | 443 de saída |

O proxy precisa:

- terminar TLS com certificado válido para o nome público;
- encaminhar `POST /twilio/whatsapp`, `POST /twilio/whatsapp/status` e `POST /webhook` sem alterar o corpo;
- preservar os cabeçalhos `X-Twilio-Signature` e `Authorization`;
- usar timeout de leitura de pelo menos 20 segundos;
- não reescrever host, porta, caminho nem query das URLs assinadas pela Twilio.

### 4.2 PostgreSQL da trilha operacional

| Recurso | Piloto |
|---|---|
| Versão | PostgreSQL 14 ou superior |
| CPU e memória | 2 vCPU, 4 GB |
| Disco | 50 GB |
| Schema | `observability` |
| Retenção | 30 dias, por job da CONTRATANTE |
| Pool da aplicação | até 2 conexões |

A trilha não grava telefone, CPF, conteúdo da mensagem, token nem texto clínico.

### 4.3 Variáveis de ambiente

```env
HTTP_PORT=8080
SIMULADOR_ENABLED=false
ADMIN_USER=admin
ADMIN_PASSWORD=

ORACLE_JDBC_URL=jdbc:oracle:thin:@//host-tasy:1521/SERVICO
ORACLE_USER=USR_MANDALORE
ORACLE_PASSWORD=

DIALOGFLOW_PROJECT_ID=
DIALOGFLOW_CREDENTIALS=/opt/mandalore/segredos/dialogflow.json
DIALOGFLOW_WEBHOOK_USER=
DIALOGFLOW_WEBHOOK_PASSWORD=
DIALOGFLOW_ENVIRONMENT=
DIALOGFLOW_LOCATION=

TWILIO_ENABLED=true
TWILIO_ACCOUNT_SID=AC...
TWILIO_AUTH_TOKEN=
TWILIO_WHATSAPP_FROM=whatsapp:+55...
TWILIO_WEBHOOK_URL=https://nome-publico/twilio/whatsapp
TWILIO_STATUS_CALLBACK_URL=https://nome-publico/twilio/whatsapp/status

OBSERVABILITY_POSTGRES_ENABLED=true
OBSERVABILITY_POSTGRES_INITIALIZE_SCHEMA=false
OBSERVABILITY_POSTGRES_JDBC_URL=jdbc:postgresql://host-pg:5432/mandalore
OBSERVABILITY_POSTGRES_USER=
OBSERVABILITY_POSTGRES_PASSWORD=
```

`TWILIO_WEBHOOK_URL` e `TWILIO_STATUS_CALLBACK_URL` precisam ser idênticas às URLs configuradas no console da Twilio (protocolo, host, porta, caminho e query). Qualquer diferença invalida a assinatura e o Mandalore responde 403.

`DIALOGFLOW_CREDENTIALS` aponta para o JSON da conta de serviço no disco do servidor, legível só pelo usuário do processo.

### 4.4 Endpoints

| Método | Caminho | Quem chama | Autenticação | Público |
|---|---|---|---|---|
| POST | `/twilio/whatsapp` | Twilio | `X-Twilio-Signature` | Sim |
| POST | `/twilio/whatsapp/status` | Twilio | `X-Twilio-Signature` | Sim |
| POST | `/webhook` | Dialogflow | HTTP Basic | Sim |
| GET | `/health` | Monitor | Nenhuma | Não |
| GET | `/actuator/health/liveness` e `/actuator/health/readiness` | Orquestrador | Nenhuma | Não |
| GET | `/admin` (central: `/curadoria`, `/precos`, `/simulador`) | Navegador | Login da central (`ADMIN_USER`) | Sim |

### 4.5 Saída de rede

| Destino | Uso |
|---|---|
| `dialogflow.googleapis.com` ou `{regiao}-dialogflow.googleapis.com` | `detectIntent` |
| `oauth2.googleapis.com` | Token da conta de serviço |
| `api.twilio.com` | Envio da resposta |
| Listener Oracle do Tasy | Leitura e gravação da agenda |
| PostgreSQL da trilha | Gravação da trilha |

### 4.6 Log

O log registra `message_sid`, intenção, latência e status. É proibido registrar Auth Token, senha, CPF, telefone completo e conteúdo da mensagem.

## 5. Conexão com o Tasy

| Parâmetro | Valor |
|---|---|
| Driver | `oracle.jdbc.OracleDriver` (ojdbc11) |
| Pool | Hikari, `agendamento-conversa` |
| Máximo de conexões | 5 |
| Timeout de conexão | 3 s |
| Timeout de cada comando SQL | 3 s |
| Criptografia de rede | obrigatória, AES256 |
| Checksum | obrigatório, SHA1 |
| Out-of-band | desligado |

O `sqlnet` do servidor Oracle precisa aceitar criptografia nativa AES256 com checksum SHA1.

### 5.1 Usuário Oracle

Usuário de serviço exclusivo, distinto do owner do Tasy, com sinônimos ou schema padrão que resolvam os nomes sem prefixo.

```sql
GRANT CREATE SESSION TO USR_MANDALORE;

GRANT SELECT ON TASY.PESSOA_FISICA TO USR_MANDALORE;
GRANT SELECT ON TASY.ESPECIALIDADE_MEDICA TO USR_MANDALORE;
GRANT SELECT ON TASY.CONVENIO TO USR_MANDALORE;
GRANT SELECT ON TASY.AGENDA TO USR_MANDALORE;
GRANT SELECT ON TASY.PROC_INTERNO TO USR_MANDALORE;
GRANT SELECT ON TASY.PROC_INTERNO_ORIENTACAO TO USR_MANDALORE;

GRANT SELECT, UPDATE ON TASY.AGENDA_CONSULTA TO USR_MANDALORE;
GRANT SELECT, UPDATE ON TASY.AGENDA_PACIENTE TO USR_MANDALORE;
```

Não há `INSERT` nem `DELETE` em tabela do Tasy. Não há `UPDATE` em `PESSOA_FISICA`. O owner (`TASY` no exemplo) é o do ambiente da CONTRATANTE.

A idempotência das gravações usa uma tabela própria do Mandalore, fora das tabelas do Tasy, criada no PostgreSQL da trilha ou em schema separado definido no levantamento.

## 6. Limites de caracteres e de tempo

### 6.1 Canal, Dialogflow e Twilio

| Limite | Valor | Efeito |
|---|---|---|
| Texto do paciente enviado ao Dialogflow | 256 caracteres | O excedente é cortado. A frase longa perde o final |
| Identificador de sessão do Dialogflow | 36 caracteres, `A-Z a-z 0-9 _ -` | Sessão maior é substituída por id derivado |
| Webhook de fulfillment do Dialogflow | 5 segundos e 64 KiB | Fora disso o webhook falha. O SQL do Tasy é limitado a 3 s para caber |
| Cota Dialogflow ES, texto | 180 req/min no trial; 600 no Essentials | Um agente por região no projeto |
| `detectIntent` na aplicação | conexão 2 s, leitura 8 s | Estouro gera a mensagem de contingência |
| Mensagem WhatsApp de saída | a Twilio rejeita acima de 1600 caracteres | O Mandalore parte em blocos de até 1500, em fronteira de parágrafo e linha |
| Janela de atendimento WhatsApp | 24 horas após a última mensagem do paciente | Fora dela só template aprovado, fora deste escopo |
| `error_message` da trilha | 1000 caracteres | Somente erro operacional |
| CPF informado | 11 dígitos depois de remover pontuação | Outro tamanho não identifica |
| Telefone WhatsApp | `whatsapp:+` e dígitos em E.164 | Fora do formato, o webhook responde 400 |
| Horários oferecidos por busca | 8 | Os primeiros a partir do início do filtro |

Datas aceitas: `AAAA-MM-DD` e `dd/MM/aaaa` (também com `-` ou `.`). Ano com dois dígitos soma 1900. Datas exibidas em `dd/MM/yyyy` e horas em `HH:mm`. Manhã é hora menor que 12. Tarde é hora maior ou igual a 12.

### 6.2 Campos do Tasy gravados ou exibidos

Os tamanhos abaixo são os do dicionário Tasy de referência. A CONTRATANTE confere na versão instalada antes do go-live.

| Tabela.coluna | Tamanho | Uso |
|---|---|---|
| `PESSOA_FISICA.CD_PESSOA_FISICA` | 10 | Id do paciente e do profissional |
| `PESSOA_FISICA.NM_PESSOA_FISICA` | 60 | Nome exibido |
| `PESSOA_FISICA.NR_CPF` | 11 | Identificação |
| `AGENDA_CONSULTA.IE_STATUS_AGENDA` | 2 | Status do horário de consulta |
| `AGENDA_CONSULTA.NM_PACIENTE` | 80 | Gravado. O nome coletado é truncado em 80 |
| `AGENDA_CONSULTA.CD_PESSOA_FISICA` | 10 | Gravado |
| `AGENDA_CONSULTA.NM_USUARIO` | 15 | Gravado com o usuário técnico `MANDALORE` |
| `AGENDA_PACIENTE.IE_STATUS_AGENDA` | 3 | Status do horário de exame |
| `AGENDA_PACIENTE.NM_PACIENTE` | 60 | Gravado. O nome é truncado em 60 |
| `AGENDA_PACIENTE.CD_PESSOA_FISICA` | 10 | Gravado |
| `AGENDA_PACIENTE.NM_USUARIO` | 15 | Gravado com `MANDALORE` |
| `PROC_INTERNO.DS_PROC_EXAME` | 400 | Nome do exame exibido |

## 7. Mapeamento Tasy — leitura

### 7.1 `PESSOA_FISICA`

| Coluna | Uso |
|---|---|
| `CD_PESSOA_FISICA` | Id do paciente e do profissional da agenda |
| `NM_PESSOA_FISICA` | Nome exibido |
| `DT_NASCIMENTO` | Comparada pela parte data |
| `NR_CPF` | Comparado sem `.`, `-` e `/` |

```sql
TRANSLATE(NR_CPF, '.-/', '') = :cpf
AND CAST(DT_NASCIMENTO AS DATE) = :nascimento
```

Mais de uma pessoa com o mesmo par CPF e nascimento é tratado como não identificado.

### 7.2 `AGENDA`

| Coluna | Uso |
|---|---|
| `CD_AGENDA` | Agenda do horário |
| `DS_AGENDA` | Local exibido |
| `CD_TIPO_AGENDA` | `3` consulta; `2` exame |
| `CD_PESSOA_FISICA` | Profissional da agenda de consulta |
| `CD_ESPECIALIDADE` | Especialidade da agenda de consulta |
| `IE_SITUACAO` | Somente `A` |

### 7.3 `AGENDA_CONSULTA`

| Coluna | Uso |
|---|---|
| `NR_SEQUENCIA` | Id do horário |
| `CD_AGENDA` | Liga com `AGENDA` |
| `DT_AGENDA` | Início do horário |
| `IE_STATUS_AGENDA` | Domínio abaixo |
| `CD_PESSOA_FISICA` | Paciente marcado |
| `CD_CONVENIO` | Convênio já usado, para ordenar a lista |
| `CD_ESPECIALIDADE` | Preferido ao da agenda quando preenchido |

| `IE_STATUS_AGENDA` | Significado no Mandalore |
|---|---|
| `L` | Livre. Único status oferecido para marcar |
| `N` | Marcado |
| `CN` | Confirmado |
| `M` | Marcado (considerado nas consultas futuras) |

Disponibilidade: status `L`, agenda ativa tipo 3, `DT_AGENDA` no intervalo, médico, especialidade e período opcionais, até 8 linhas.

Consultas futuras: paciente igual a `CD_PESSOA_FISICA`, tipo 3, status `N`, `CN` ou `M`, `DT_AGENDA` a partir de hoje.

### 7.4 `CONVENIO`

| Coluna | Uso |
|---|---|
| `CD_CONVENIO` | Id escolhido |
| `DS_CONVENIO` | Texto da lista |
| `IE_SITUACAO` | Somente `A` |

### 7.5 `ESPECIALIDADE_MEDICA`

| Coluna | Uso |
|---|---|
| `CD_ESPECIALIDADE` | Id |
| `DS_ESPECIALIDADE` | Texto da lista |
| `IE_SITUACAO` | Somente `A` |

### 7.6 `AGENDA_PACIENTE`

| Coluna | Uso |
|---|---|
| `NR_SEQUENCIA` | Id do horário de exame |
| `CD_AGENDA` | Liga com `AGENDA` |
| `HR_INICIO` | Início exibido |
| `IE_STATUS_AGENDA` | `L` é livre |
| `NR_SEQ_PROC_INTERNO` | Exame. No horário livre pode ser nulo |

O exame oferecido em uma agenda é inferido das marcações anteriores dessa agenda, porque o horário livre de exame no Tasy não traz procedimento.

### 7.7 `PROC_INTERNO`

| Coluna | Uso |
|---|---|
| `NR_SEQUENCIA` | Id do exame |
| `DS_PROC_EXAME` | Nome exibido |
| `IE_SITUACAO` | Somente `A` |
| `CD_PROCEDIMENTO` | Gravado na marcação de exame |
| `IE_ORIGEM_PROCED` | Gravado na marcação de exame |

### 7.8 `PROC_INTERNO_ORIENTACAO`

| Coluna | Uso |
|---|---|
| `NR_SEQ_PROC_INTERNO` | Exame |
| `IE_TIPO_ORIENTACAO` | Somente `P` |
| `DS_ORIENTACAO` | Texto, com HTML removido |
| `NR_SEQUENCIA` | Ordem |

## 8. Mapeamento Tasy — gravação

Toda gravação é um `UPDATE` condicionado ao status atual. Se outra pessoa ocupou o horário antes, o comando altera zero linhas e o paciente recebe "horário indisponível". Não há dupla reserva.

### 8.1 Marcar consulta

Condição: `CD_AGENDA = :agenda AND DT_AGENDA = :data AND IE_STATUS_AGENDA = 'L'`.

| Coluna | Valor gravado |
|---|---|
| `IE_STATUS_AGENDA` | `N` |
| `CD_PESSOA_FISICA` | Id do paciente identificado; nulo no paciente sem cadastro |
| `NM_PACIENTE` | Nome completo, até 80 |
| `DT_NASCIMENTO_PAC` | Data de nascimento informada |
| `CD_CONVENIO` | Convênio escolhido na conversa |
| `DT_AGENDAMENTO` | Data e hora do servidor |
| `DT_ATUALIZACAO` | Data e hora do servidor |
| `NM_USUARIO` | `MANDALORE` |

### 8.2 Marcar exame

Condição: `CD_AGENDA = :agenda AND HR_INICIO = :data AND IE_STATUS_AGENDA = 'L'`.

| Coluna | Valor gravado |
|---|---|
| `IE_STATUS_AGENDA` | `N` |
| `CD_PESSOA_FISICA` | Id do paciente |
| `NM_PACIENTE` | Nome, até 60 |
| `DT_NASCIMENTO_PAC` | Data de nascimento |
| `CD_CONVENIO` | Convênio escolhido |
| `CD_PROCEDIMENTO` | `PROC_INTERNO.CD_PROCEDIMENTO` |
| `IE_ORIGEM_PROCED` | `PROC_INTERNO.IE_ORIGEM_PROCED` |
| `NR_SEQ_PROC_INTERNO` | `PROC_INTERNO.NR_SEQUENCIA` |
| `DT_ATUALIZACAO` | Data e hora do servidor |
| `NM_USUARIO` | `MANDALORE` |

### 8.3 Confirmar consulta

Condição: `NR_SEQUENCIA = :sequencia AND CD_PESSOA_FISICA = :paciente AND IE_STATUS_AGENDA IN ('N','CN','M')`.

| Coluna | Valor |
|---|---|
| `IE_STATUS_AGENDA` | `CN` |
| `DT_CONFIRMACAO` | Data e hora do servidor |
| `DT_ATUALIZACAO` | Data e hora do servidor |
| `NM_USUARIO` | `MANDALORE` |

### 8.4 Cancelar consulta

Mesma condição da confirmação.

| Coluna | Valor |
|---|---|
| `IE_STATUS_AGENDA` | `L` |
| `CD_PESSOA_FISICA`, `NM_PACIENTE`, `DT_NASCIMENTO_PAC`, `CD_CONVENIO`, `DT_AGENDAMENTO`, `DT_CONFIRMACAO` | Nulo |
| `DT_CANCELAMENTO` | Data e hora do servidor |
| `DT_ATUALIZACAO` | Data e hora do servidor |
| `NM_USUARIO` | `MANDALORE` |

O cancelamento devolve o horário à oferta. Motivo de cancelamento (`CD_MOTIVO_CANCELAMENTO`) não é gravado; se o hospital exigir, entra como parametrização no levantamento.

### 8.5 Remarcar consulta

Uma transação: marca o novo horário (8.1) e cancela o atual (8.4). Se qualquer passo falhar, nada é gravado.

### 8.6 Idempotência

Cada gravação leva uma chave derivada da conversa e do horário. Repetir a mesma mensagem não grava de novo.

### 8.7 Regras do Tasy

O `UPDATE` direto não executa as rotinas de tela do Tasy (consistências de convênio, bloqueio de turno, telemedicina, regras de encaixe). Triggers de banco existentes nas tabelas continuam a disparar. A CONTRATANTE valida, com a Philips quando necessário, que esse modo de gravação é aceito no seu ambiente antes da produção.

## 9. Twilio e WhatsApp

### 9.1 Papel da Twilio

A Twilio é o provedor (BSP) do WhatsApp Business. A Meta opera a rede do WhatsApp. O Mandalore recebe o webhook da Twilio e envia pela API REST da conta, com o SDK oficial `twilio-java`.

A conta, a fatura e o número pertencem à CONTRATANTE. Mensagens e metadados trafegam pela Twilio e pela Meta, que a CONTRATANTE registra como operadoras no seu inventário LGPD.

### 9.2 Etapas burocráticas

| # | Providência | Responsável | Observação |
|---|---|---|---|
| 1 | Conta Twilio verificada, com pagamento e Brasil liberado em Geo Permissions | CONTRATANTE | Conta trial não sustenta número oficial |
| 2 | Portfólio comercial na Meta (Business Manager) com o CNPJ do hospital | CONTRATANTE | A empresa que aparece para o paciente |
| 3 | Verificação da empresa na Meta | CONTRATANTE | Documentos societários, endereço e site. Prazo da Meta |
| 4 | WhatsApp Business Account (WABA) vinculada à conta Twilio | CONTRATANTE, com apoio da CONTRATADA | Um WABA por operação |
| 5 | Nome de exibição | CONTRATANTE | Deve corresponder à marca verificada |
| 6 | Número de telefone | CONTRATANTE | Fora do aplicativo WhatsApp comum e de outro BSP. Recebe código por SMS ou voz |
| 7 | Perfil comercial | CONTRATANTE | Foto, descrição, endereço, site, categoria, e-mail |
| 8 | Opt-in do paciente | CONTRATANTE | Esta versão só responde a quem escreveu primeiro |
| 9 | Sender aprovado | CONTRATANTE | Em seguida, `TWILIO_WHATSAPP_FROM=whatsapp:+55DDDNUMERO` |
| 10 | Webhook de entrada e status callback | CONTRATADA configura; CONTRATANTE publica DNS e certificado | URLs da seção 4.3, método POST |
| 11 | Templates | Fora do escopo | Exigidos para mensagem fora da janela de 24 h |
| 12 | Qualidade e limite de envio | Meta | Conta nova começa em faixa baixa; bloqueios reduzem a faixa |
| 13 | Política comercial do WhatsApp | CONTRATANTE | Sem conteúdo proibido e sem envio a quem não optou |

Enquanto o sender oficial não é aprovado, a homologação usa o Sandbox da Twilio (`whatsapp:+14155238886`), que só conversa com números que enviaram `join <código>`. O Sandbox não atende paciente real.

### 9.3 Comportamento implementado

- Credenciais lidas do ambiente.
- POST sem assinatura válida recebe 403.
- Todos os campos recebidos entram na validação da assinatura.
- `MessageSid` repetido não é processado de novo.
- Mensagem sem texto recebe: "Consigo seguir por texto. Pode digitar a opção?"
- Respostas usam texto livre, sem template, dentro da janela de 24 horas.
- O callback de status registra `MessageStatus` e `ErrorCode`.
- Texto acima de 1500 caracteres sai em várias mensagens.
- Em falha: "Não consegui continuar agora. Pode tentar novamente."

## 10. Dialogflow

| Item | Valor |
|---|---|
| Produto | Dialogflow ES, API v2 |
| Idioma | `pt-BR` |
| Fuso | `America/Sao_Paulo` |
| Confiança mínima | 0,3 |
| Produção | environment publicado; região quando não global |
| Webhook | `https://<host>/webhook`, Basic Auth |
| Conta de serviço | papel de cliente da API Dialogflow |

## 11. Critérios de aceite

1. `GET /health` retorna `status=UP`.
2. Readiness fica UP com Oracle e trilha alcançáveis.
3. CPF e nascimento existentes são reconhecidos.
4. Horário `L` de agenda ativa tipo 3 aparece entre as opções.
5. A marcação muda o horário para `N`, com paciente e `NM_USUARIO = 'MANDALORE'`, e ele deixa de ser oferecido.
6. Confirmação muda para `CN`; cancelamento devolve para `L`.
7. Marcação de exame grava procedimento em `AGENDA_PACIENTE`.
8. Duas marcações simultâneas no mesmo horário resultam em uma única gravação.
9. Mensagem de WhatsApp de teste recebe resposta pelo número oficial.
10. POST sem assinatura da Twilio recebe 403; `/webhook` sem Basic Auth recebe 401.
11. `SIMULADOR_ENABLED=false`.
12. Log de teste sem CPF, telefone completo e tokens.

## 12. Responsabilidades contínuas da CONTRATANTE

- Renovar o certificado HTTPS.
- Manter a conta Twilio paga e a qualidade do número na Meta.
- Rotacionar a senha Oracle e o JSON da conta Google, informando a CONTRATADA.
- Executar o expurgo da trilha operacional.
- A cada atualização do Tasy, conferir que `CD_TIPO_AGENDA` 2 e 3 e os status `L`, `N`, `CN` e `M` mantêm o significado, e que as colunas da seção 8 continuam existindo com o mesmo tamanho.
