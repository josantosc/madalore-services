# Retomada de agendamento abandonado

**Situação: não iniciado.** Este documento guarda o desenho para montar o processo no futuro. Faz parte da
Fase 1 (relacionamento proativo) do [`roadmap.md`](roadmap.md).

O paciente começa a marcar pelo bot e para no meio. O Mandalore percebe, espera um pouco e manda uma mensagem que
retoma do ponto em que ele parou. É a "recuperação de carrinho abandonado" do e-commerce, adaptada à clínica. Nome
comercial sugerido: **recuperação de agendamento**.

## Por que fazer

- Cada abandono é uma consulta ou um exame que o paciente queria e não marcou.
- Dentro da janela de 24 horas do WhatsApp a mensagem é livre: não paga template e não precisa de aprovação da
  Meta.
- Usa o fluxo de agendamento que já existe. A retomada só traz o paciente de volta para ele.

## Pré-requisitos

1. WhatsApp oficial no Chatwoot (item 2 do roteiro).
2. Motor de envio da Fase 1: rotina agendada e tabela de envios no Postgres, os mesmos do lembrete D-1.
3. Etapa do agendamento gravada na trilha (abaixo).

## 1. Detectar o abandono

Hoje a trilha registra o desfecho da conversa (`Relatorio.Conversao`): o bot marcou ou passou para atendente.
Conversa com mensagem e sem nenhum dos dois é um abandono.

Falta gravar a **última etapa alcançada**, sem fala nem dado do paciente:

| Etapa | Intenção | Tratamento |
|---|---|---|
| Escolheu a especialidade ou o exame | baixa | uma mensagem leve |
| Viu os horários e não escolheu | alta | até duas mensagens |
| Escolheu o horário e parou na identificação | muito alta | até duas mensagens; se não responder, vai para a Recepção |

Parar na identificação costuma ser atrito (CPF não encontrado, dúvida), não desistência.

## 2. Cadência

- 1ª mensagem entre 30 minutos e 2 horas depois da última fala do paciente.
- 2ª mensagem perto de 20 horas depois, antes de fechar a janela de 24 horas.
- No máximo duas tentativas por conversa. Fora da janela não envia: template de marketing custa e aumenta bloqueio
  do número.
- Envia só das 8h às 20h, de segunda a sábado. Fora disso, adia para a próxima abertura se ainda estiver dentro da
  janela.
- Os tempos ficam em configuração por clínica, para ajustar pelo teste A/B.

## 3. Mensagem que retoma do ponto

A rotina consulta o Tasy de novo antes de enviar. Se o horário visto ainda estiver livre, oferece ele:

> Você estava marcando cardiologia. O horário de quinta, às 14h, ainda está livre. Quer que eu reserve?

Se não estiver, oferece os próximos horários da mesma especialidade ou exame.

Botões: **Reservar**, **Outros horários**, **Falar com atendente**, **Não quero mais**. A resposta cai no fluxo
de agendamento existente, com os contextos do Dialogflow refeitos a partir da etapa gravada.

## 4. Motivo do abandono (Gemini)

O Gemini lê as últimas falas e devolve só uma categoria. Ele não escreve para o paciente: cada categoria tem um
texto fixo, aprovado pela clínica. Mesma regra do orçamento por foto.

| Motivo | Ação |
|---|---|
| Sem horário | Oferece a lista de espera (Fase 1, item 5) |
| Preço | Texto de valores do `faq.yaml` ou transbordo para o Faturamento |
| Convênio não atendido | Oferece particular ou transbordo para a Recepção |
| Dúvida (preparo, endereço, documentos) | Responde pelo `faq.yaml` e retoma o agendamento |
| Só pesquisando | Uma mensagem leve, sem segunda tentativa |
| Urgência | Não retoma. Orienta procurar o pronto-socorro |
| Indefinido | Mensagem padrão da etapa |

A trilha grava a categoria, nunca o texto da conversa.

## 5. Quando não enviar

- Paciente já tem agendamento futuro no Tasy para a mesma especialidade ou exame (marcou por outro canal).
- Conversa passou para atendente, ou está aberta com um humano no Chatwoot.
- Paciente pediu para não receber mensagens.
- Já recebeu retomada nos últimos 30 dias.
- Motivo classificado como urgência.

**Especialidade sensível** (psiquiatria, oncologia, infectologia, saúde da mulher e outras da lista da clínica):
a mensagem não cita a especialidade, só "seu agendamento". Outra pessoa pode ver a notificação no celular.

## 6. Prioridade e transbordo

Nota de prioridade de cada abandono:

- valor do exame ou da consulta pela tabela de `/precos`;
- paciente novo (sem cadastro no Tasy);
- particular.

Abandono de prioridade alta que não respondeu à última mensagem vira conversa Aberta no Chatwoot, com a etiqueta
`retomada` e uma nota privada (etapa, especialidade, horário visto, motivo). A Recepção liga ou escreve.

## 7. Dados

Tabela `retomada_envio` no Postgres, uma linha por tentativa:

| Coluna | Uso |
|---|---|
| `conta_chatwoot`, `conversa_id` | conversa de origem |
| `etapa`, `motivo` | etapa alcançada e categoria do Gemini |
| `tentativa` | 1 ou 2 |
| `enviado_em`, `respondido_em` | cadência e tempo de resposta |
| `desfecho` | `agendou`, `recusou`, `atendente`, `sem_resposta`, `suprimido` |

Chave única em (`conversa_id`, `tentativa`) para não enviar duas vezes em reprocessamento.

## 8. Indicadores

- **Taxa de recuperação:** retomadas que viraram agendamento.
- **Receita recuperada estimada:** agendamentos recuperados vezes o preço da tabela. É o número que vende o módulo.
- Recuperação por etapa e por motivo.
- Taxa de "Não quero mais" e de descadastro, para saber se a cadência está agressiva.
- Teste A/B de tempo da primeira mensagem e de texto.

Os números entram no relatório do OpenClaw, sem dado de paciente.

## Passos de implantação

1. Gravar a etapa do agendamento na trilha.
2. Criar a tabela `retomada_envio` e a rotina que busca abandonos a cada 15 minutos.
3. Regras de supressão e janela de horário.
4. Mensagem por etapa, com consulta ao Tasy antes do envio.
5. Classificação do motivo pelo Gemini, com textos fixos por motivo.
6. Transbordo com etiqueta `retomada` para os de prioridade alta.
7. Indicadores no relatório e jornada na regressão local.
8. Ligar por clínica (`RETOMADA_ENABLED`), começando só com a 1ª mensagem e sem o Gemini.
