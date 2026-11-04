# CONTRATO DE PROGRAMA PILOTO (BETATESTE) Nº PRJ#[●] — MANDALORE

Minuta. Campos marcados com `[●]` dependem do comercial ou do cliente. Este instrumento cobre somente o piloto. A contratação definitiva segue o `contrato-mandalore.md` e o ANEXO II `especificacao-implantacao.md`.

## CONDIÇÕES ESPECÍFICAS

**I. CONTRATANTE:** [RAZÃO SOCIAL DO HOSPITAL] — CNPJ/MF [●] — Endereço [●]

**II. CONTRATADA:** DIGISYSTEM SERVIÇOS ESPECIALIZADOS LTDA — CNPJ/MF 01.936.069/0001-94 — Avenida Paulista, nº 1439, 4º andar, cj 43/44, Bela Vista, São Paulo – SP, CEP 01311-200.

**III. OBJETO:** Direito de uso temporário do software MANDALORE — assistente de agendamento por WhatsApp integrado ao Tasy — em regime de programa piloto, com implantação assistida, acompanhamento e coleta de indicadores, no escopo do ANEXO A.

**IV. NATUREZA:** O MANDALORE está em versão de pré-lançamento (0.1.x). A CONTRATANTE reconhece que funcionalidades, textos e fluxos podem mudar durante o piloto e que o piloto existe para validar a solução em operação real.

**V. VIGÊNCIA:** 90 (noventa) dias corridos, contados do go-live em produção (ANEXO B, fase 3), prorrogáveis uma vez por até 60 dias mediante aceite por e-mail. A fase de preparação não conta no prazo.

**VI. VALOR:** Conforme ANEXO C.

**VII. CONVERSÃO:** Ao fim do piloto, a CONTRATANTE poderá contratar a licença definitiva nas condições do ANEXO C, item 3. Sem a conversão, o contrato se encerra sem multa.

**VIII. RESCISÃO:** Qualquer PARTE poderá encerrar o piloto com aviso prévio de 15 (quinze) dias, sem multa, pagando-se os valores proporcionais ao período utilizado.

**IX. FORO:** Comarca de São Paulo/SP.

---

## CONDIÇÕES GERAIS

### CLÁUSULA 1 – ESCOPO

1.1. O escopo funcional do piloto é exclusivamente o do ANEXO A. Qualquer funcionalidade fora dele, inclusive as listadas no ANEXO A, item 3, depende de termo aditivo.

1.2. A solução opera sobre serviços de terceiros — Twilio (provedor WhatsApp), Meta (rede WhatsApp) e Google (Dialogflow) — contratados e pagos diretamente pela CONTRATANTE.

1.3. A gravação no Tasy é feita pelo serviço de integração de agendamento (agendamento-ds) ou por `UPDATE` condicionado ao status do horário, conforme definido no levantamento (ANEXO B, fase 1). A CONTRATANTE valida com a Philips, quando necessário, que o modo de gravação escolhido é aceito no seu ambiente.

### CLÁUSULA 2 – OBRIGAÇÕES DA CONTRATADA

a) Instalar, configurar e publicar a solução nos ambientes da CONTRATANTE.
b) Corrigir sem custo defeitos da solução dentro do escopo do ANEXO A durante o piloto.
c) Entregar relatório quinzenal de indicadores (ANEXO D) e relatório final.
d) Atuar como OPERADORA de dados pessoais (LGPD), tratando-os só para o objeto deste contrato.
e) Manter a solução sem registro em log de CPF, telefone completo, conteúdo da mensagem, tokens ou senhas.
f) Gravar no Tasy apenas nas tabelas, colunas e condições do ANEXO A, item 2.

### CLÁUSULA 3 – OBRIGAÇÕES DA CONTRATANTE

a) Prover servidor de aplicação, proxy HTTPS com certificado válido, PostgreSQL da trilha operacional e conectividade com o Oracle do Tasy (ANEXO II, itens 4 e 5).
b) Criar o usuário Oracle de serviço com os grants necessários e, quando usado, disponibilizar o agendamento-ds.
c) Contratar, em seu nome, conta Twilio, WhatsApp Business Account na Meta e projeto Google Cloud.
d) Indicar ponto focal com poder de decisão e responder às pendências em até 2 dias úteis.
e) Atuar como CONTROLADORA dos dados, definir a base legal, obter o opt-in do paciente e indicar o Encarregado (DPO).
f) Manter canal de atendimento humano divulgado ao paciente durante todo o piloto.
g) Responder pelo conteúdo institucional e clínico apresentado ao paciente, inclusive orientações de preparo de exame cadastradas no Tasy e o texto de orientações gerais.
h) Fornecer os dados de linha de base do ANEXO D antes do go-live.
i) Autorizar, ao fim do piloto, a citação do nome da CONTRATANTE como cliente e a divulgação de indicadores agregados, sem dados de pacientes. A autorização pode ser recusada por escrito no aceite.

### CLÁUSULA 4 – RESPONSABILIDADES E LIMITES

4.1. A responsabilidade da CONTRATADA limita-se ao prejuízo efetivo comprovado, até o valor total pago neste contrato, desde que demonstrado dolo ou culpa exclusiva.

4.2. A CONTRATADA não responde por indisponibilidade, mudança de política, bloqueio de número ou limitação de envio impostos por Twilio, Meta ou Google, nem por falhas do Tasy, do Oracle ou da infraestrutura da CONTRATANTE.

4.3. A CONTRATADA não responde por agendamento decorrente de dado incorreto informado pelo paciente, cadastro incorreto no Tasy ou parametrização de agendas, convênios e procedimentos.

4.4. A solução não presta orientação médica, triagem ou atendimento de urgência.

4.5. Não há SLA contratual durante o piloto. A CONTRATADA atende incidentes em horário comercial, com meta de início em até 4 horas úteis para indisponibilidade total.

### CLÁUSULA 5 – PROPRIEDADE INTELECTUAL E CONFIDENCIALIDADE

5.1. O software, o código-fonte, o agente Dialogflow, scripts e documentação pertencem exclusivamente à CONTRATADA. O piloto concede direito de uso não exclusivo, intransferível e limitado à vigência. Os dados da CONTRATANTE permanecem de sua titularidade.

5.2. Sugestões e feedbacks da CONTRATANTE podem ser incorporados ao produto sem remuneração ou exclusividade.

5.3. As PARTES manterão sigilo sobre informações recebidas por 5 (cinco) anos após o encerramento.

5.4. Incidente de segurança com dados pessoais será comunicado em até 48 horas pela PARTE que dele tomar conhecimento.

### CLÁUSULA 6 – ENCERRAMENTO

6.1. Encerrado o piloto sem conversão, cessa o direito de uso. A CONTRATANTE desativa os webhooks da Twilio e do Dialogflow que apontam para o Mandalore e revoga o usuário Oracle de serviço.

6.2. Agendamentos já gravados no Tasy permanecem válidos e sob gestão da CONTRATANTE.

### CLÁUSULA 7 – DISPOSIÇÕES FINAIS

7.1. Aplicam-se, no que couber, as Leis nº 9.609/98, 9.610/98, 12.846/13 e 13.709/18.

7.2. As PARTES aceitam assinatura digital (MP nº 2.200-2/2001) e dispensam testemunhas quando a integridade for conferida por provedor de assinatura (art. 784, § 4º, CPC).

7.3. Havendo divergência entre este contrato e seus anexos, prevalece o contrato.

São Paulo/SP, [●] de [●] de 2026.

_____________________________________
[CONTRATANTE]

_____________________________________
DIGISYSTEM SERVIÇOS ESPECIALIZADOS LTDA

---

## ANEXO A — ESCOPO FUNCIONAL DO PILOTO

### 1. Fluxos incluídos

O paciente escreve para o número oficial do hospital e recebe o menu:

```
1. Marcar consulta
2. Marcar exame
```

#### 1.1 Marcar consulta (paciente cadastrado)

1. O paciente informa CPF e data de nascimento.
2. O Mandalore localiza o cadastro em `PESSOA_FISICA`. Mais de um cadastro com o mesmo par é tratado como não identificado.
3. O paciente escolhe o convênio. Convênios já usados por ele aparecem primeiro.
4. O paciente escolhe por profissional ou por especialidade.
5. O paciente escolhe manhã, tarde ou qualquer período, e opcionalmente um dia ou "esta semana".
6. O Mandalore oferece até 8 horários livres (status `L`) de agendas ativas de consulta (`CD_TIPO_AGENDA = 3`).
7. O paciente escolhe um horário e confirma com "sim".
8. O Mandalore grava o agendamento no Tasy e devolve o resumo: especialidade, data, hora, profissional e convênio.

#### 1.2 Marcar exame (paciente cadastrado)

1. O paciente informa CPF e data de nascimento.
2. O paciente escolhe o convênio.
3. O Mandalore lista os exames ativos (`PROC_INTERNO.IE_SITUACAO = 'A'`) que têm horário livre futuro em agenda ativa de exame (`CD_TIPO_AGENDA = 2`). O vínculo entre agenda e exame é inferido das marcações anteriores da agenda, porque o horário livre de exame no Tasy não traz o procedimento.
4. O paciente escolhe o período e, opcionalmente, o dia. Sem dia informado, a busca cobre os próximos 30 dias.
5. O Mandalore oferece até 8 horários livres em `AGENDA_PACIENTE`, com o local da agenda.
6. O paciente escolhe e confirma com "sim".
7. O Mandalore grava o exame em `AGENDA_PACIENTE` com o procedimento (`CD_PROCEDIMENTO`, `IE_ORIGEM_PROCED`, `NR_SEQ_PROC_INTERNO`).
8. O Mandalore envia o resumo (exame, data, hora, local e convênio) seguido de:
   - a orientação de preparo do exame cadastrada em `PROC_INTERNO_ORIENTACAO` (tipo `P`), com o HTML removido;
   - as orientações gerais do hospital (documentos, endereço, acompanhante, estacionamento), em texto fornecido pela CONTRATANTE.

Se a orientação de preparo não puder ser lida, o agendamento continua válido e o paciente recebe o resumo e as orientações gerais.

#### 1.3 Comportamentos comuns

- Opção inválida pede nova escolha. "Reiniciar" começa uma nova conversa.
- Sem horário no período, o paciente pode trocar período, dia ou profissional.
- Dois pacientes no mesmo horário resultam em uma única gravação; o segundo recebe "horário indisponível".
- A mesma mensagem repetida não grava de novo.
- Mensagem sem texto recebe pedido para digitar a opção.
- Respostas acima de 1500 caracteres saem em várias mensagens.
- Em falha do Tasy ou do Dialogflow, o paciente recebe mensagem de contingência e nada é gravado.

### 2. Gravações no Tasy

| Fluxo | Tabela | Condição | Valores |
|---|---|---|---|
| Consulta | `AGENDA_CONSULTA` | `CD_AGENDA`, `DT_AGENDA` e `IE_STATUS_AGENDA = 'L'` | Status `N`, paciente, nome (até 80), nascimento, convênio, datas e `NM_USUARIO = 'MANDALORE'` |
| Exame | `AGENDA_PACIENTE` | `CD_AGENDA`, `HR_INICIO` e `IE_STATUS_AGENDA = 'L'` | Status `N`, paciente, nome (até 60), nascimento, convênio, procedimento e `NM_USUARIO = 'MANDALORE'` |

Não há `INSERT` nem `DELETE` em tabela do Tasy e não há alteração em `PESSOA_FISICA`.

Quando a gravação usar o agendamento-ds, a marcação de exame exige `CD_INTEGRACAO` preenchido no `PROC_INTERNO` dos exames ofertados, ou a adoção do `UPDATE` condicionado descrito acima para `AGENDA_PACIENTE`. A opção é decidida na fase 1 do ANEXO B.

### 3. Fora do piloto

Estes itens existem no roadmap e podem entrar por aditivo ou na contratação definitiva:

- marcação para paciente sem cadastro no Tasy;
- listar, confirmar, cancelar e remarcar agendamentos pelo WhatsApp;
- lembrete e confirmação ativa (exigem template aprovado pela Meta);
- transbordo para atendente humano no mesmo número;
- áudio, imagem, documento e botões nativos do WhatsApp;
- autorização de convênio, guias, faturamento, encaixe e telemedicina;
- outros canais (site, aplicativo, Instagram);
- mais de uma unidade ou mais de um número de WhatsApp.

Enquanto o item 3 não estiver contratado, o menu de "ver, remarcar ou cancelar" fica oculto, e o paciente que pedir essas operações é orientado a usar o canal humano.

---

## ANEXO B — CRONOGRAMA

| Fase | Atividade | Responsável principal | Duração estimada |
|---|---|---|---|
| 0 | Assinatura, abertura da Twilio, início da verificação na Meta | CONTRATANTE | Prazo da Meta |
| 1 | Levantamento: agendas, convênios, exames, status, modo de gravação e texto de orientações gerais | CONTRATADA | 1 semana |
| 2 | Instalação em homologação e teste integrado com o Sandbox da Twilio | CONTRATADA | 2 semanas |
| 2a | Homologação de consulta pela CONTRATANTE | CONTRATANTE | 1 semana |
| 2b | Homologação de exame pela CONTRATANTE | CONTRATANTE | 1 semana |
| 3 | Go-live em produção com o número oficial aprovado | Ambas | 1 dia |
| 4 | Operação assistida e relatórios quinzenais | CONTRATADA | 90 dias |
| 5 | Relatório final e decisão de conversão | Ambas | 1 semana |

O go-live de consulta pode acontecer antes do de exame, se a homologação 2b atrasar.

Critérios de aceite da homologação: itens 1 a 5, 7, 8, 9, 10, 11 e 12 do ANEXO II, item 11.

---

## ANEXO C — CONDIÇÕES COMERCIAIS DO PILOTO

### 1. Valores

| Item | Valor de tabela | Valor no piloto |
|---|---|---|
| Setup e implantação | R$ [●] | R$ [●] (desconto de [●]%) |
| Licença e acompanhamento mensal | R$ [●] | R$ [●] por mês |

O pagamento do setup ocorre em até 30 dias da assinatura. A mensalidade é faturada a partir do mês do go-live.

### 2. Custos de terceiros (pagos pela CONTRATANTE)

| Fornecedor | Item |
|---|---|
| Meta | Mensagens WhatsApp. Desde 01/10/2026, R$ 0,035 por mensagem de serviço após as 1.000 grátis por número ao mês |
| Twilio | Taxa por mensagem e número |
| Google Cloud | Dialogflow ES, por requisição de texto |
| Infraestrutura | Servidor, PostgreSQL e certificado HTTPS |

### 3. Conversão

Contratando a licença definitiva em até 30 dias do fim do piloto:

- o valor pago de setup no piloto é abatido do setup definitivo;
- a mensalidade definitiva tem desconto de [●]% nos primeiros 12 meses;
- a CONTRATANTE recebe sem custo de implantação o pacote "Gestão de agendamentos" (listar, confirmar, cancelar e remarcar), se ele estiver disponível na data da conversão.

---

## ANEXO D — INDICADORES DO PILOTO

A CONTRATANTE fornece a linha de base dos 3 meses anteriores ao go-live. A CONTRATADA mede pela trilha operacional e por consulta ao Tasy (`NM_USUARIO = 'MANDALORE'`).

| Indicador | Fonte | Meta sugerida |
|---|---|---|
| Conversas iniciadas | Trilha | Informativo |
| Consultas marcadas pelo WhatsApp | Tasy | [●] por mês |
| Exames marcados pelo WhatsApp | Tasy | [●] por mês |
| Taxa de conclusão (conversas com marcação / conversas com CPF identificado) | Trilha | ≥ [40]% |
| Marcações fora do horário comercial | Tasy | Informativo |
| Tempo médio da conversa até a marcação | Trilha | ≤ [5] minutos |
| Falhas de integração (Tasy, Dialogflow, Twilio) | Trilha | ≤ [2]% das conversas |
| Faltas nos agendamentos feitos pelo WhatsApp vs. linha de base | Tasy | Informativo |
| Ligações e mensagens recebidas pela recepção vs. linha de base | CONTRATANTE | Informativo |

O relatório final compara os indicadores com a linha de base e sustenta a decisão de conversão.
