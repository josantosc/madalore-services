# Manual de atendimento: assistente virtual, equipes e orçamento por foto

**BioSaúde. Para recepção, laboratório, faturamento/convênios e financeiro.**

Este manual explica o que o assistente virtual faz sozinho, quando ele passa a conversa para uma pessoa, para qual
equipe ela vai e o que cada um faz a partir daí.

---

## 1. Visão geral

1. O paciente escreve no site ou no WhatsApp.
2. O **assistente virtual** responde primeiro. Ele agenda, remarca, cancela e consulta horários, convênios e
   especialidades.
3. A conversa passa para uma pessoa em dois casos:
   - o paciente **pede um atendente**;
   - o paciente **manda a foto ou o PDF de um pedido médico**.
4. O assistente escolhe a **equipe** pelo assunto, e o Chatwoot entrega a conversa a um **atendente** dessa equipe.
5. Enquanto a conversa está com uma pessoa, o assistente **fica em silêncio**.

---

## 2. Três palavras que confundem

| Termo no Chatwoot | O que é | Exemplo |
|---|---|---|
| **Caixa de entrada** | Por onde o paciente chegou. **Nunca muda.** | WhatsApp, site |
| **Equipe** | O setor responsável pela conversa | Recepção, Laboratório, Faturamento/Convênios |
| **Atendente** | A pessoa que responde | Maria, da Recepção |

Uma conversa que mostra "WhatsApp" pode estar na equipe do Faturamento. O canal mostra a origem; a equipe mostra
quem cuida.

---

## 3. Quando o assistente passa para uma pessoa

### 3.1 O paciente pede um atendente

Basta escrever algo como "atendente", "quero falar com alguém" ou "falar com uma pessoa".

O assistente responde:

> Vou chamar alguém da equipe. O histórico desta conversa fica com a pessoa.

Em seguida, a conversa:

- recebe a **equipe** pelo assunto (item 4);
- vai para **Abertas**;
- é entregue a um atendente da equipe que esteja disponível.

### 3.2 O paciente manda a foto do pedido médico

Vale foto ou PDF. O assistente responde:

> Recebi o seu pedido. Vou conferir os exames e um atendente te passa o orçamento por aqui em instantes.

Em seguida, o assistente lê o pedido, monta um **orçamento sugerido** numa **nota privada** e encaminha a conversa
ao **Faturamento/Convênios** (item 5).

### 3.3 O assistente não entende

O assistente responde:

> Não entendi. Pode reformular ou pedir um atendente?

Nesse caso a conversa **não** é passada sozinha. Ela só vai para uma pessoa se o paciente pedir.

---

## 4. Para qual equipe vai a conversa

O assistente procura palavras do assunto **na mensagem em que o paciente pede o atendente**. Se essa mensagem não
diz o assunto, como em "quero um atendente", vale **a última mensagem da conversa que dizia**.

| Equipe | O paciente falou de... | Exemplos de palavras |
|---|---|---|
| **Faturamento/Convênios** | dinheiro, convênio ou orçamento | convênio, guia, autorização, carteirinha, reembolso, nota fiscal, boleto, pagamento, pagar, cobrança, fatura, glosa, orçamento |
| **Laboratório** | exame já feito ou coleta | resultado, laudo, coleta, recoleta, amostra, jejum, preparo, urina, fezes, sangue |
| **Recepção** | qualquer outra coisa | agendar, remarcar, horário, endereço, dúvidas gerais |

Se a mensagem tiver palavras de Faturamento **e** de Laboratório, vale o **Faturamento**. Exemplo: "o convênio cobre
a coleta?".

### Exemplos

| O paciente escreveu | Equipe |
|---|---|
| "Meu convênio cobre esse exame? Quero falar com um atendente" | Faturamento/Convênios |
| "Cadê o resultado do meu exame?" e depois "atendente" | Laboratório |
| "Preciso de jejum para o exame de sangue?" e depois "falar com alguém" | Laboratório |
| "Quero falar com uma pessoa" | Recepção |
| "Não achei horário para cardiologista" e depois "atendente" | Recepção |
| Mandou a foto do pedido médico | Faturamento/Convênios |
| Mandou uma foto que não é pedido médico (documento, receita de remédio) | Recepção |

### Errou de equipe?

Acontece, porque a regra se baseia em palavras. O atendente que recebeu a conversa troca a equipe no painel da
direita da conversa (**Equipe**) e, se quiser, usa a resposta pronta **/transferir**:

> Vou transferir seu atendimento para o setor responsável. Em instantes alguém continua com você por aqui.

---

## 5. Orçamento por foto do pedido médico

**Regra principal: o assistente nunca informa preço ao paciente. Quem envia o orçamento é sempre uma pessoa.**

### O que o Faturamento recebe

Uma **nota privada** na conversa, com fundo amarelo, que **o paciente não vê**:

```
Orçamento sugerido pela leitura automática do pedido. Confira com a foto antes de enviar.

--- Texto para o paciente ---
Orçamento dos exames do seu pedido (particular):
- Hemograma completo: R$ 25,00 (resultado em 1 dia útil)
- Glicose: R$ 12,50 (resultado em 1 dia útil)
Total: R$ 37,50
Valores para pagamento particular. Se for usar convênio, me diga qual é.

Sem preço na tabela (verificar):
- Anti-TPO
```

### O que o atendente do Faturamento faz

1. **Abre a foto** que o paciente mandou e confere se os exames da nota batem com o pedido.
2. Confere a lista **"Sem preço na tabela"**. São exames que o assistente leu, mas que não estão na tabela de preços.
   O atendente informa o valor manualmente e avisa o financeiro para incluí-los na tabela.
3. Copia o trecho **"Texto para o paciente"**, ajusta o que for preciso e **envia como mensagem normal**, não como
   nota privada.
4. Se o paciente for usar **convênio**, segue o fluxo de autorização em vez do valor particular.

### Outras notas que podem aparecer

| Nota privada | O que fazer |
|---|---|
| "A imagem enviada não parece um pedido médico de exames." | A conversa vai para a **Recepção**. Veja o anexo e responda o paciente. |
| "Não identifiquei os exames na foto do pedido (foto cortada, tremida ou letra ilegível)." | Peça outra foto com a resposta pronta **/pedido-foto**. |
| "Não consegui ler a foto do pedido automaticamente." | Monte o orçamento olhando a foto. |

### Cuidados

- O assistente lê **apenas os exames**. Ele não copia nome, documento nem dados do paciente para a nota.
- A leitura automática **pode errar**, principalmente com letra de médico difícil. Sempre confira com a foto.
- Se a conversa já está com um atendente, uma nova foto **não** gera orçamento automático. O atendente monta
  manualmente.

---

## 6. Tabela de preços (financeiro)

Os valores do orçamento vêm da **tabela de preços particular**, que o financeiro mantém em
`https://bs-api.jscinfor.com/precos`, com usuário e senha próprios.

- **Adicionar exame:** código, nome, valor, prazo do resultado e sinônimos.
- **Sinônimos** são outros nomes do mesmo exame, como "HMG" e "Hemograma" para "Hemograma completo". Quanto mais
  sinônimos, mais exames o assistente reconhece no pedido.
- A aba **Sem preço** mostra exames ainda com R$ 0,00. Esses aparecem no orçamento com valor zero e precisam ser
  preenchidos.
- **Exportar** e **Importar** permitem editar a tabela inteira no Excel. A importação **substitui** a tabela toda.
- Uma alteração vale **já no próximo orçamento**.

Quando o Faturamento avisar de um exame em "Sem preço na tabela", o financeiro inclui o exame ou acrescenta o nome
lido como sinônimo de um exame existente.

---

## 7. Durante e depois do atendimento

| Situação | O que acontece |
|---|---|
| Conversa **Aberta** (com uma pessoa) | O assistente não responde. Só o atendente fala. |
| Atendente muda o status para **Pendente** | A conversa **volta para o assistente**, que responde à próxima mensagem do paciente. |
| Atendente **Resolve** a conversa | O atendimento termina. Se o paciente escrever de novo, o assistente atende primeiro. |

Respostas prontas úteis (digite `/` na caixa de mensagem):

| Atalho | Quando usar |
|---|---|
| `/saudacao` | Ao assumir a conversa |
| `/aguarde` | Ao precisar de tempo para verificar algo |
| `/transferir` | Ao trocar a equipe da conversa |
| `/pedido-foto` | Para pedir foto nítida do pedido médico |
| `/valores` | Quando o paciente pergunta preço sem mandar pedido |
| `/lgpd` | Antes de falar de resultado ou dado de saúde |
| `/encerramento` | Antes de resolver a conversa |

---

## 8. Regras de ouro

1. **Preço só por uma pessoa.** O assistente nunca envia valores.
2. **Nota privada não é para o paciente.** Copie o texto para uma mensagem normal antes de enviar.
3. **Confira sempre com a foto.** A leitura automática é uma sugestão.
4. **Equipe errada:** troque a equipe na conversa e avise o paciente com `/transferir`.
5. **Dados de saúde:** confirme a identidade antes (`/lgpd`).

---

## 9. Perguntas frequentes

**A conversa diz "WhatsApp", mas eu sou do Faturamento. Está errado?**
Não. "WhatsApp" é o canal por onde o paciente chegou. Veja a **Equipe** no painel da direita.

**Por que a conversa caiu comigo e não com um colega?**
O Chatwoot distribui entre os membros da equipe que estão disponíveis. Para receber conversas de uma equipe, o
atendente precisa estar **na equipe** e **na caixa de entrada**. Fale com o administrador.

**O paciente mandou a foto, mas não apareceu orçamento.**
Se a conversa já estava aberta com um atendente, o assistente não lê a foto. Monte o orçamento manualmente.

**O orçamento veio com R$ 0,00.**
O exame está na tabela sem preço. Informe o valor ao paciente e peça ao financeiro para preencher na tabela.

**O paciente quer usar convênio.**
O orçamento automático é sempre particular. Siga o fluxo de autorização do convênio.

---

*Para o administrador: as regras técnicas e a configuração das equipes (IDs e variáveis
`CHATWOOT_EQUIPE_*`) estão em [`chatwoot.md`](chatwoot.md). O orçamento por foto está em
[`orcamento-pedido.md`](orcamento-pedido.md).*
