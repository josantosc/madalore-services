# Decisão: como escalar o produto para vários clientes

Data: 06/11/2026. Vale para Mandalore, Dialogflow, Chatwoot e OpenClaw.

## Decisão

**Uma pilha completa por cliente (hospital ou rede), em servidor próprio, igual em todos os clientes e
administrada por um único painel do Coolify.**

| Peça | Por cliente | Compartilhado entre clientes |
|---|---|---|
| Servidor (VPS) | Sim | Não |
| Chatwoot | Sim, com uma conta por unidade da rede | Não |
| Mandalore e PostgreSQL da trilha | Sim, com uma entrada em `clientes.properties` por unidade | Não |
| Agente Dialogflow (projeto Google) | Sim | Não. O que se compartilha é o modelo do agente no repositório |
| OpenClaw, bot do Telegram e chave do Gemini | Sim | Não |
| Painel do Coolify | Não | Sim: um painel administra os servidores de todos os clientes |
| Código, imagens, modelos de configuração, scripts e roteiros | Não | Sim: mesma versão para todos |

Cliente é quem responde pelos dados perante a LGPD (o controlador). Unidades de uma mesma rede hospitalar ficam
na mesma pilha, usando as várias contas do Chatwoot e o `clientes.properties`, que já existem para isso.

## Por quê

1. **Dado de paciente separado por construção.** O Chatwoot guarda o conteúdo das conversas, e o Mandalore lê e grava
   no Tasy. Com uma pilha por cliente, um erro de configuração, um token vazado ou um comando indevido ao agente do
   OpenClaw atinge um hospital só. É o argumento que o hospital, como controlador, cobra em contrato.
2. **Cada peça já é de um cliente só.** O Oracle de cada hospital libera a porta para o IP do servidor dele. O
   WhatsApp, a Twilio e o projeto Google são do hospital ([especificação](especificacao-implantacao.md), seção 3).
   A cota do Dialogflow é por agente. O OpenClaw foi feito para um único dono e não isola agentes entre si.
3. **O custo cabe.** Um VPS e o backup custam uma fração pequena da mensalidade dos planos
   ([mercado](mercado-monetizacao.md#51-planos-propostos)). O mercado é de poucos clientes de valor alto, não de
   milhares de clínicas pequenas.
4. **O mesmo pacote serve para os dois modelos de contrato.** A pilha que roda no nosso VPS (serviço gerenciado) é a
   mesma que se instala no servidor do hospital, quando o contrato prevê infraestrutura dele.
5. **Atualização e desligamento por cliente.** Dá para atualizar um hospital por vez, voltar versão de um só e
   entregar ou apagar os dados de um cliente no fim do contrato sem tocar nos outros.

## Alternativas descartadas

| Alternativa | Por que não agora |
|---|---|
| Tudo compartilhado: um Chatwoot com várias contas, um Mandalore com vários hospitais, um OpenClaw com um agente por cliente | Mistura conversas de pacientes de controladores diferentes no mesmo banco. Os relatórios somam a instalação inteira. O OpenClaw não isola agentes. Uma falha derruba todos |
| Várias pilhas no mesmo VPS | Todos os hospitais liberam o Oracle para o mesmo IP, um cliente pesado afeta os outros e um acesso indevido ao servidor expõe todos. Economia pequena diante do risco |
| Um projeto Dialogflow para vários clientes | Cota dividida, custo sem separação e uma publicação errada afeta todos. O projeto é do hospital por contrato |

## O que torna fácil

Escalar vira repetir um procedimento padronizado, sem decisões novas a cada cliente:

| Item | Situação |
|---|---|
| Roteiro de servidor, Chatwoot e Mandalore | Pronto: [deploy](deploy-coolify-hostinger.md), [Chatwoot](chatwoot.md) |
| Roteiro do OpenClaw com problemas conhecidos | Pronto: [implantação do OpenClaw](openclaw-implantacao-cliente.md) |
| Publicação do agente Dialogflow | Pronto: `scripts/publicar_dialogflow.py` |
| Instalação da skill do OpenClaw | Pronto: `scripts/openclaw_instalar_skill.sh` |
| Ficha única do cliente (domínios, IDs, onde está cada segredo) cobrindo as quatro peças | A fazer: hoje há a ficha do OpenClaw |
| Servidores dos clientes cadastrados no mesmo painel do Coolify | A fazer a partir do segundo cliente |
| Script dos agendamentos do OpenClaw | A fazer |
| Mesma versão fixada de Chatwoot, OpenClaw e Mandalore em todos os clientes, atualizada em ondas (homologação, um cliente, os demais) | Regra a seguir |

Padrão de nomes por cliente, com a sigla do cliente (`bs` na BioSaúde):

| Peça | Domínio |
|---|---|
| Mandalore | `<sigla>-api.<dominio>` |
| Chatwoot | `<sigla>-chat.<dominio>` |
| OpenClaw | `<sigla>-openclaw.<dominio>` |

Segredos de cada cliente ficam no cofre de senhas, numa pasta por cliente, e no Coolify. Nunca no repositório.

## Plano para clínicas pequenas

Clínicas pequenas pagam uma mensalidade próxima do custo de um servidor, e a integração com o sistema delas costuma
demorar. Para elas:

- **Várias clínicas na mesma pilha**, uma conta do Chatwoot por clínica. A separação é pela conta do Chatwoot, que já
  vale para token, segredo, perguntas frequentes e, agora, para a agenda.
- **Agenda no PostgreSQL do Mandalore** desde o primeiro dia, sem esperar a integração. O bot marca nela e a recepção
  repete no sistema da clínica pelas pendências da tela `/agenda`, até a integração ficar pronta
  ([agenda do Mandalore](agenda-mandalore.md)).
- **Login da recepção por clínica**, que só enxerga a agenda da própria conta.

Os relatórios do OpenClaw, inclusive o da agenda, continuam somando a instalação inteira. Antes de vender o plano
para clínicas sem relação entre si, fazer a melhoria "Relatórios por clínica" abaixo.

## Quando rever

Reabrir esta decisão se acontecer um destes casos:

- O plano para clínicas pequenas passar de algumas clínicas por pilha.
- Mais de 20 clientes ativos, quando o custo de operar servidores separados passar a pesar.

Nesse caso, o caminho é compartilhar só o Mandalore e o Chatwoot, depois de a trilha gravar a conta do Chatwoot em
cada evento e de o token de cada API ficar preso a uma conta (melhoria "Relatórios por clínica" em
[funcionalidades e melhorias](funcionalidades-e-melhorias.md)). O OpenClaw continua um por cliente.
