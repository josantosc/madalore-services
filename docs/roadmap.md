# Roteiro de evolução do Mandalore

Ordem sugerida das próximas entregas, o que já existe de cada uma e o que falta. Situação em 05/11/2026.

| Prioridade | Item | Esforço | Por quê | Situação |
|---|---|---|---|---|
| 1 | Perguntas frequentes no bot | baixo | Tira volume do atendente logo | Feito, falta preencher os textos da clínica |
| 2 | WhatsApp oficial no Chatwoot | baixo/médio | É onde está o paciente | Parcial |
| 3 | Lembrete D-1 com confirmação | médio | Reduz faltas, o maior retorno financeiro | Não iniciado |
| 4 | Preparo de exame enviado sozinho | baixo | O texto já existe | Parcial |
| 5 | Automações e equipes no Chatwoot | baixo | Organiza o humano | Parcial |
| 6 | Satisfação + convite de avaliação no Google | baixo | Reputação local | Não iniciado |
| 7 | Orçamento por foto do pedido | médio/alto | Diferencial de laboratório | Feito |
| 8 | Resultado de exame pelo bot | alto | Exige integração e cuidado com a LGPD | Não iniciado |

O item 7 foi adiantado porque o laboratório pediu. Os itens 4 e 6 usam o mesmo envio proativo do item 3: depois
do lembrete, os dois ficam baratos.

---

## 1. Perguntas frequentes no bot

**Feito.** Detalhes em [`perguntas-frequentes.md`](perguntas-frequentes.md).

- Nove temas: endereço, horário, convênios, preparo, resultado, documentos, estacionamento, contato e valores
  (intents `acao.faq.<tema>`).
- O texto vem do `faq.yaml` de cada clínica, com texto próprio por conta do Chatwoot.
- A pergunta no meio de um agendamento não perde o passo. Tema sem texto oferece atendente.
- Jornada na regressão local (`perguntas-frequentes.json`).

**Próximos passos**

1. Preencher o `faq.yaml` da clínica a partir de [`deploy/faq.exemplo.yaml`](../deploy/faq.exemplo.yaml) e montar
   no Coolify em `/app/config/faq.yaml`.
2. Publicar o agente (`py scripts/publicar_dialogflow.py`) e fazer a prova no simulador.
3. Na curadoria, revisar as falas não entendidas das primeiras semanas: ensinar frases nos temas existentes ou
   criar tema novo.
4. Acompanhar a queda de "% não entendidas" e de transbordos para a Recepção, e os `faq_sem_resposta` na trilha.

---

## 2. WhatsApp oficial no Chatwoot

**Já existe**

- O Mandalore trata `Channel::Whatsapp` pelo `/chatwoot`, com mensagens de até 1500 caracteres
  ([`chatwoot.md`](chatwoot.md), seção Canais).
- O canal Twilio direto funciona no Sandbox ([`twilio-whatsapp.md`](twilio-whatsapp.md)), mas sem atendente
  humano no mesmo número.
- O transbordo, as equipes e o orçamento por foto funcionam em qualquer caixa do Chatwoot.

**Próximos passos**

1. Escolher o provedor da caixa WhatsApp no Chatwoot: WhatsApp Cloud API (Meta direto) ou Twilio.
2. Verificar o portfólio comercial da clínica na Meta e aprovar o número e o nome de exibição.
3. Criar a caixa **WhatsApp** na conta do hospital, vincular o `bot-agenda` e incluir os agentes.
4. Com o número no Chatwoot, desligar `TWILIO_ENABLED` para o mesmo número não ser atendido duas vezes.
5. Fazer a prova do [`chatwoot.md`](chatwoot.md) pelo celular: agendamento, transbordo e foto do pedido.
6. Atualizar o Anexo II ([`especificacao-implantacao.md`](especificacao-implantacao.md)): o atendimento humano no
   mesmo número deixa de estar fora do escopo.
7. Trocar o link `wa.me` no Instagram, no site e no Google pelo número oficial.

---

## 3. Lembrete D-1 com confirmação

**Já existe**

- Confirmar (status `CN`), cancelar e remarcar consulta pelo bot, gravando no Tasy (intents
  `acao.agenda.confirmar`, `acao.agenda.cancelar`, `acao.agenda.remarcar`).
- Leitura dos agendamentos futuros do paciente (`acao.agenda.meus_agendamentos`).

**Próximos passos**

1. Aprovar na Meta um template de utilidade com os botões Confirmar, Remarcar e Cancelar.
2. Rotina agendada (por exemplo, às 10h) que lê no Tasy as consultas e os exames do dia seguinte de cada conta.
3. Enviar o template pela API do Chatwoot (ou pela Twilio, com `ContentSid`), abrindo uma conversa Pendente com
   o bot.
4. Tabela de envios no Postgres: um lembrete por agendamento, mesmo em reprocessamento.
5. Tratar a resposta do botão no fluxo que já existe de confirmar, cancelar e remarcar.
6. Respeitar quem pediu para não receber mensagens. Não enviar para paciente sem celular válido.
7. Relatório semanal: enviados, confirmados, cancelados, sem resposta e faltas.
8. Tirar o lembrete da lista de fora do escopo no Anexo II e prever o custo do template na proposta
   ([`mercado-monetizacao.md`](mercado-monetizacao.md)).

---

## 4. Preparo de exame enviado sozinho

**Já existe**

- Ao marcar exame pelo bot, a confirmação traz a orientação de preparo do Tasy (`agenda.orientacaoExame`) e as
  orientações gerais de [`orientacoes/exame-geral.txt`](../src/main/resources/orientacoes/exame-geral.txt).

**Falta**

- Quem marcou pela recepção ou pelo telefone não recebe o preparo.

**Próximos passos**

1. Na rotina do item 3, mandar o preparo junto com o lembrete de exame. Se o exame exigir jejum longo, mandar na
   véspera, antes do jantar.
2. Exame sem orientação no Tasy recebe só as orientações gerais, e a lista vai para o laboratório completar o
   cadastro.
3. Template próprio de utilidade para o preparo, se a Meta não aceitar o texto dentro do lembrete.

---

## 5. Automações e equipes no Chatwoot

**Já existe**

- Equipe por assunto no transbordo (`CHATWOOT_EQUIPE_RECEPCAO`, `_LABORATORIO`, `_FATURAMENTO`), com
  atribuição automática ([`chatwoot.md`](chatwoot.md), seção Transbordo).
- Respostas prontas `/saudacao`, `/aguarde`, `/transferir`, `/pedido-foto`, `/valores`, `/lgpd` e `/encerramento`
  ([`manual-atendimento-equipes.md`](manual-atendimento-equipes.md)).

**Próximos passos**

1. Horário de atendimento na caixa, com mensagem de ausência que avisa quando a equipe volta e que o assistente
   segue agendando. Conferir se ela não responde antes do bot nas conversas Pendentes.
2. Regras de automação: etiqueta por canal e por equipe, prioridade para "resultado" e "reclamação", aviso à
   equipe quando uma conversa aberta fica sem resposta.
3. Resolver sozinha a conversa sem mensagem há 24 horas, para ela voltar ao bot na próxima vez.
4. Painel de relatórios por equipe: tempo da primeira resposta e volume por dia.
5. Registrar a configuração no manual, para replicar no próximo hospital.

---

## 6. Satisfação + convite de avaliação no Google

**Já existe**

- Nada específico. A conversa resolvida pelo atendente encerra o atendimento ([`manual-atendimento-equipes.md`](manual-atendimento-equipes.md), item 7).

**Próximos passos**

1. Ligar a pesquisa de satisfação (CSAT) da caixa no Chatwoot, enviada quando a conversa é resolvida.
2. Testar se a resposta da pesquisa não aciona o bot nem reabre a conversa.
3. Nota 4 ou 5: mandar o link de avaliação do Perfil da Empresa no Google. Nota 1 a 3: abrir a conversa para a
   Recepção, sem pedir avaliação.
4. Após o atendimento só pelo bot (agendamento concluído), mandar o mesmo convite, no máximo uma vez a cada
   90 dias por paciente.
5. Fora da janela de 24 horas do WhatsApp o convite precisa de template.

---

## 7. Orçamento por foto do pedido

**Feito.** Detalhes em [`orcamento-pedido.md`](orcamento-pedido.md).

- O Gemini lê o pedido e deixa uma nota privada com o orçamento sugerido para o Faturamento. O bot nunca envia
  valor ao paciente.
- Tela de preços do financeiro em `/precos`, importação e exportação em CSV.
- Central de administração em `/admin`, com login único para curadoria, preços e simulador.
- O simulador aceita foto, PDF e câmera.

**Pendências de implantação**

1. Preencher a tabela real de preços e prazos, com sinônimos dos exames mais pedidos.
2. Ativar a Vertex AI e o papel **Vertex AI User** no projeto de produção, e `ORCAMENTO_ENABLED=true` no Coolify.
3. Nas primeiras semanas, o Faturamento anota os erros de leitura. Exames frequentes em "Sem preço na tabela"
   viram item ou sinônimo.
4. Avaliar orçamento por convênio, que hoje é sempre particular.

---

## 8. Resultado de exame pelo bot

**Já existe**

- Pergunta de resultado vai para a equipe Laboratório.
- Resposta pronta `/lgpd` para confirmar a identidade antes de falar de dado de saúde.
- Identificação do paciente por CPF e data de nascimento.

**Próximos passos**

1. Definir a fonte do resultado: laudo liberado no Tasy ou o sistema do laboratório.
2. Relatório de impacto (RIPD) e aprovação do encarregado de dados do hospital.
3. Identificação mais forte que CPF e nascimento: protocolo do atendimento ou código enviado ao celular
   cadastrado.
4. O bot informa só se o resultado está pronto e manda um link autenticado com validade curta. Não manda o PDF
   nem os valores do exame na conversa.
5. Resultado crítico ou de menor de idade vai sempre para o Laboratório.
6. Registrar cada acesso (quem, quando, qual atendimento) sem gravar o conteúdo do laudo.
