# Funcionalidades e melhorias do Mandalore

O que o Mandalore já faz e o que está na fila. A ordem das melhorias e o passo a passo de cada uma estão em
[`roadmap.md`](roadmap.md). Situação em 05/11/2026.

## Funcionalidades prontas

### Conversa com o paciente

| Funcionalidade | Detalhe | Documento |
|---|---|---|
| Identificar o paciente | CPF e data de nascimento, lidos no Tasy | [`especificacao-implantacao.md`](especificacao-implantacao.md) |
| Marcar consulta | Por especialidade ou por médico, com até 8 horários livres, gravando no Tasy | idem |
| Marcar consulta sem cadastro | Agendamento nominal, sem criar `PESSOA_FISICA` | idem |
| Marcar exame | Lista de exames, horários e confirmação com o preparo do Tasy e as orientações gerais | idem |
| Ver, confirmar, cancelar e remarcar consulta | Grava status `CN`, libera o horário ou troca na mesma transação | idem |
| Recuperação sem horário | Oferece outro período, nova busca ou atendente | [`testes-regressao-conversacional.md`](testes-regressao-conversacional.md) |
| Voltar e reiniciar | "voltar" desfaz um passo, "reiniciar" começa de novo | idem |
| Perguntas frequentes | Endereço, horário, convênios, preparo, resultado, documentos, estacionamento, contato e valores, com o texto de cada clínica | [`perguntas-frequentes.md`](perguntas-frequentes.md) |

### Canais e atendimento humano

| Funcionalidade | Detalhe | Documento |
|---|---|---|
| Chatwoot | Site, WhatsApp, Instagram e Messenger pelo mesmo `/chatwoot`, com mensagens divididas pelo limite do canal | [`chatwoot.md`](chatwoot.md) |
| WhatsApp pela Twilio | Canal direto, validado no Sandbox | [`twilio-whatsapp.md`](twilio-whatsapp.md) |
| Instagram levando ao WhatsApp | Respostas automáticas e link `wa.me`, sem código | [`instagram-para-whatsapp.md`](instagram-para-whatsapp.md) |
| Transbordo | O paciente pede atendente e a conversa abre para a equipe do assunto (Recepção, Laboratório, Faturamento) | [`manual-atendimento-equipes.md`](manual-atendimento-equipes.md) |
| Orçamento por foto do pedido | O Gemini lê os exames e deixa uma nota privada para o Faturamento. O bot nunca envia valor | [`orcamento-pedido.md`](orcamento-pedido.md) |

### Operação e administração

| Funcionalidade | Detalhe | Documento |
|---|---|---|
| Várias clínicas numa instalação | Uma conta do Chatwoot por clínica, com token, segredo, Oracle e perguntas frequentes próprios | [`chatwoot.md`](chatwoot.md) |
| Central de administração | `/admin` com login único para curadoria, preços e simulador | [`orcamento-pedido.md`](orcamento-pedido.md) |
| Curadoria | Falas não entendidas, indicadores e "Ensinar" frase no Dialogflow | [`curadoria.md`](curadoria.md) |
| Tabela de preços | Tela do financeiro com importação e exportação em CSV | [`orcamento-pedido.md`](orcamento-pedido.md) |
| Simulador | Conversa com o agente real, com foto, PDF e câmera | [`testes-regressao-conversacional.md`](testes-regressao-conversacional.md) |
| Trilha operacional | Eventos de cada mensagem no PostgreSQL | [`observabilidade-postgres.md`](observabilidade-postgres.md) |
| Relatórios pelo OpenClaw | Resumo diário da curadoria, conversão diária e relatório semanal do gestor, só com números. Card de conversão na curadoria. O OpenClaw lê por API, envia ao Telegram e responde perguntas avulsas no grupo | [`relatorios-openclaw.md`](relatorios-openclaw.md), [implantação por cliente](openclaw-implantacao-cliente.md) |
| Testes | Regressão local por cenário, Robot no Dialogflow e no widget | [`testes-regressao-conversacional.md`](testes-regressao-conversacional.md) |
| Deploy | Coolify na Hostinger | [`deploy-coolify-hostinger.md`](deploy-coolify-hostinger.md) |

## Melhorias priorizadas

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

O que já existe e os próximos passos de cada item: [`roadmap.md`](roadmap.md).

## Outras melhorias

Itens fora da tabela, sem prioridade definida.

| Melhoria | Motivo | Esforço |
|---|---|---|
| Ver, cancelar e remarcar exame | Hoje só a consulta tem esse fluxo | médio |
| Orçamento por convênio | O orçamento por foto é sempre particular | médio |
| Áudio do paciente | Mensagem de voz recebe pedido para digitar. Transcrever e seguir a conversa | médio |
| Botões e listas nativos do WhatsApp | Menos digitação e menos erro de opção | médio |
| Instagram e Messenger dentro do Chatwoot | Atender sem mandar o paciente para o WhatsApp | baixo/médio (app aprovado na Meta) |
| Idempotência compartilhada da Twilio | A proteção contra reenvio fica em memória; com mais de uma réplica, usar tabela ou Redis | baixo |
| Indicadores de negócio na central | Os números do relatório semanal em tela, com faltas quando houver o lembrete | médio |
| Relatórios por clínica | Os relatórios somam a instalação inteira. Gravar a conta do Chatwoot em cada evento da trilha e filtrar a API por conta, para um Mandalore atender várias clínicas com relatórios separados | médio |
| Cadastro de paciente novo no Tasy | Hoje o agendamento sem cadastro é nominal | alto (validação com a Philips) |
