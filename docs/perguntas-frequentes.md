# Perguntas frequentes no bot

O paciente pergunta endereço, horário, convênios, preparo e outras dúvidas comuns, e o bot responde sem chamar
um atendente. O texto é de cada clínica e fica num arquivo de configuração, fora do Dialogflow.

## Como funciona

1. O Dialogflow reconhece a pergunta numa intent `acao.faq.<tema>`.
2. O webhook do Mandalore busca o texto do tema para a conta do Chatwoot da conversa.
3. O bot responde o texto e acrescenta: "Posso ajudar em algo mais? Para marcar consulta ou exame, digite menu.
   Para encerrar, digite encerrar."
4. "Obrigado" recebe "Por nada!" com as mesmas opções, sem encerrar. "Encerrar", "finalizar", "sair", "tchau" ou
   "era só isso", em qualquer ponto da conversa, mandam a despedida e resolvem a conversa no Chatwoot (se ela
   estiver pendente com o bot; com atendente, fica como está).

A pergunta pode vir no meio de um agendamento. O Mandalore devolve os contextos como estavam, e o paciente
continua do passo em que parou (por exemplo, escolhendo o convênio).

Tema sem texto cadastrado responde "Essa informação eu não tenho por aqui. Se quiser, diga atendente para falar
com alguém da equipe." O log e a trilha registram `codigo=faq_sem_resposta`.

| Tema | Intent | Exemplos de pergunta |
|---|---|---|
| `endereco` | `acao.faq.endereco` | "qual o endereço", "onde fica o laboratório", "como chego aí" |
| `horario` | `acao.faq.horario` | "que horas vocês abrem", "abre sábado", "qual o horário da coleta" |
| `convenios` | `acao.faq.convenios` | "quais convênios vocês aceitam", "aceitam unimed", "atendem particular" |
| `preparo` | `acao.faq.preparo` | "precisa de jejum", "posso tomar água antes do exame" |
| `resultado` | `acao.faq.resultado` | "quando sai o resultado", "como acesso o laudo" |
| `documentos` | `acao.faq.documentos` | "o que preciso levar", "precisa de documento com foto" |
| `estacionamento` | `acao.faq.estacionamento` | "tem estacionamento", "onde deixo o carro" |
| `contato` | `acao.faq.contato` | "qual o telefone", "qual o email de vocês" |
| `valores` | `acao.faq.valores` | "quanto custa o exame", "vocês fazem orçamento" |

As frases de treino ficam em `PERGUNTAS_FREQUENTES`, no [`scripts/publicar_dialogflow.py`](../scripts/publicar_dialogflow.py).

## Textos da clínica

Arquivo YAML com um texto por tema. Modelo: [`deploy/faq.exemplo.yaml`](../deploy/faq.exemplo.yaml).
Troque tudo o que está entre colchetes antes de publicar.

```yaml
mandalore:
  faq:
    endereco: |
      Estamos na Rua Exemplo, 100 - Centro, Joinville - SC.
    horario: |
      Atendimento: segunda a sexta, das 7h às 18h.
  clientes:
    "3":
      faq:
        endereco: |
          Estamos na Rua da Outra Clínica, 50.
```

| Chave | Vale para |
|---|---|
| `mandalore.faq.<tema>` | Todas as contas |
| `mandalore.clientes.<account_id>.faq.<tema>` | Só aquela conta do Chatwoot. Substitui o texto global do tema |

O WhatsApp pela Twilio, sem Chatwoot, usa só os textos globais.

| Ambiente | Onde fica o arquivo |
|---|---|
| Local | `./faq.yaml`, na pasta do projeto, ou o caminho em `FAQ_FILE` |
| Coolify | **Storages → File Mount**, destino `/app/config/faq.yaml` (o `Dockerfile` já define `FAQ_FILE`) |

O arquivo é lido na subida. Depois de alterar, reinicie o Mandalore. Sem o arquivo, o Mandalore sobe normalmente e
todos os temas respondem com a oferta de atendente.

Regras para os textos:

- Nada de preço. Valor só por uma pessoa ([orçamento por foto](orcamento-pedido.md)).
- Nada de dado de paciente. A resposta é a mesma para todos.
- Texto curto. No WhatsApp, resposta acima de 1500 caracteres chega dividida em mais de uma mensagem.

## Publicar o agente

Depois de mudar as frases de treino:

```powershell
py scripts/publicar_dialogflow.py --export-manifest
.\scripts\testar_regressao.ps1
py scripts/publicar_dialogflow.py
```

O último comando cria ou atualiza as intents no Dialogflow e retreina o agente. Com `DIALOGFLOW_ENVIRONMENT`
definido, publique uma versão nova para valer no ambiente.

## Prova

1. No simulador (`/simulador`), envie "qual o endereço". A resposta traz o texto de `endereco` e o fechamento.
2. Comece um agendamento, pergunte "tem estacionamento" no meio e responda a opção seguinte: o fluxo continua.
3. Pergunte um tema sem texto no arquivo: chega a oferta de atendente.

## Novas perguntas

1. Na [curadoria](curadoria.md), veja as falas não entendidas que se repetem.
2. Pergunta de um tema existente: **Ensinar** a frase na intent `acao.faq.<tema>`.
3. Tema novo: inclua em `PERGUNTAS_FREQUENTES` no script, o texto no `faq.yaml`, um caso em
   [`src/test/resources/conversas/perguntas-frequentes.json`](../src/test/resources/conversas/perguntas-frequentes.json)
   se mudar o fluxo, e publique o agente.

Pergunta sobre o resultado de um paciente específico ("meu resultado saiu?") não é pergunta frequente: o paciente
pede atendente e a conversa vai para o Laboratório.
