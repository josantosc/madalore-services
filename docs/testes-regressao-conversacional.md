# Testes de regressão conversacional

São três suítes com papéis diferentes:

| Suíte | O que valida | Acessa serviços externos | Relatório visual |
|---|---|---|---|
| Regressão local (JUnit) | Regras, eventos, contextos, textos e escritas do fluxo | Não | Allure |
| Robot: frases | Reconhecimento de frases no agente Dialogflow publicado | Sim, somente o Dialogflow | Robot (`report.html`) |
| Robot: widget Chatwoot | Conversa real no widget do site, ponta a ponta | Chatwoot, Mandalore, Dialogflow e banco | Robot, com prints e vídeo |

## Regressão local

A suíte valida o fluxo como uma máquina de estados, sem acessar Twilio, Dialogflow,
Oracle ou internet. Os cenários ficam em `src/test/resources/conversas` e informam
a intent reconhecida em cada turno. Assim, os testes cobrem regras, eventos,
contextos, textos e escritas sem depender da variação do modelo de NLU.

```powershell
.\scripts\testar_regressao.ps1
```

O comando confere se o manifesto do agente está atualizado e executa todos os
testes Maven. Para abrir o relatório visual no navegador ao final:

```powershell
.\scripts\testar_regressao.ps1 -Relatorio
```

No relatório Allure, cada jornada mostra um passo por turno, com a resposta que
chegaria ao usuário, o evento disparado e os contextos ativos. A aba de anexos
traz a conversa completa. Quando um turno falha, ele aparece em vermelho com a
expectativa que não foi atendida.

Quando `scripts/publicar_dialogflow.py` for alterado, atualize o manifesto:

```powershell
py scripts/publicar_dialogflow.py --export-manifest
```

O arquivo gerado em `src/test/resources/dialogflow/agent-manifest.json` deve ser
revisado e versionado junto com a mudança.

### Adicionar uma jornada

1. Crie um JSON em `src/test/resources/conversas`.
2. Inclua o nome do arquivo em `src/test/resources/conversas/index.json`.
3. Em cada turno, informe `intent`, `texto`, parâmetros e expectativas.
4. Use `evento`, `textoContem`, `contextos`, `contextosAusentes`, `payloadContem`,
   `escritas` e `ultimaEscrita` para descrever o resultado esperado.

As dependências de agenda são determinísticas e implementadas por
`FakeAgendaBackend`. Acrescente novos dados ali somente quando um cenário exigir.

## Simulador web

Para conversar com o fluxo sem WhatsApp nem Chatwoot, ligue `SIMULADOR_ENABLED=true`
no `.env`, reinicie o Mandalore e abra `http://localhost:8080/simulador`.

A tela mostra a conversa à esquerda. À direita, para cada resposta do bot, mostra
a intent final, a ação, a latência, os contextos ativos com o lifespan, os
parâmetros reconhecidos e o erro do webhook, quando houver. Clique numa resposta
para ver os dados daquele turno. **Nova conversa** abre outra sessão no Dialogflow,
sem contextos.

O simulador usa o agente e o webhook reais, então as ações consultam e gravam no
banco configurado, e o túnel (ngrok) precisa estar no ar. Um pedido de atendente
só é sinalizado na tela, porque não há conversa no Chatwoot para transferir. A
página fica na central de administração e pede o login de `ADMIN_USER`; mesmo
assim, mantenha `SIMULADOR_ENABLED` desligado em produção fora dos testes.

## Robot Framework

As suítes ficam em `tests/robot` e rodam num ambiente virtual próprio
(`tests/robot/.venv`), para não alterar o Python global. Na primeira execução o
script cria o ambiente, instala `requirements.txt` e baixa o Chromium do Playwright.

```powershell
.\scripts\testar_robot.ps1                    # frases no Dialogflow (padrão)
.\scripts\testar_robot.ps1 -Suite chatwoot    # widget do Chatwoot
.\scripts\testar_robot.ps1 -Suite todos
.\scripts\testar_robot.ps1 -Suite chatwoot -Visivel   # mostra o navegador
.\scripts\testar_robot.ps1 -Suite todos -DryRun       # só valida a sintaxe
```

O relatório fica em `tests/robot/results/report.html`, e o `log.html` mostra cada
turno com a frase, a intent e a resposta do bot.

O script lê do `.env` apenas `DIALOGFLOW_CREDENTIALS`, `DIALOGFLOW_PROJECT_ID`,
`CHATWOOT_BASE_URL`, `CHATWOOT_API_TOKEN`, `CHATWOOT_ACCOUNT_ID` e
`CHATWOOT_WEBSITE_TOKEN`. Os valores do `.env` prevalecem sobre os do terminal.

### Frases no Dialogflow real

`tests/robot/dialogflow/frases.robot` testa se as frases caem na intent certa do
agente publicado. A biblioteca `tests/robot/libraries/DialogflowLibrary.py`
reaproveita a autenticação de `scripts/publicar_dialogflow.py`. Não chama o
webhook do Mandalore e não grava agendamentos. Execute depois de publicar mudanças
de frases de treino.

```robotframework
Pedido de atendente gera transbordo
    Usuário Envia           quero falar com atendente
    Intent Deve Ser         transbordo.atendente
    Resposta Deve Conter    alguém da equipe
```

Mantenha nesta suíte apenas frases que não dependem do webhook. Fluxos que passam
pelo Mandalore ficam na regressão local.

### Widget do Chatwoot (ponta a ponta)

`tests/robot/chatwoot/widget.robot` usa a Browser Library (Playwright). A suíte
sobe uma página local em `http://127.0.0.1:8765` que carrega o widget da caixa de
entrada Site, abre o balão e conversa como um visitante. Cada resposta esperada
gera um print, e cada caso grava um vídeo em `tests/robot/results/videos`.

Requisitos:

- Chatwoot, Mandalore e o túnel do webhook em execução.
- `CHATWOOT_WEBSITE_TOKEN` no `.env`, com o token da caixa Site (Configurações >
  Caixas de entrada > Site > Configuração, valor de `websiteToken`). Sem ele, a
  suíte é ignorada (SKIP). O script tenta buscá-lo pela API com
  `CHATWOOT_API_TOKEN`, mas isso só funciona com token de agente ou administrador.
  O token do bot, usado pelo Mandalore, recebe 401 nessa consulta.

Nenhum caso confirma agendamento. O caso de atendente abre a conversa para
atendimento humano no Chatwoot; resolva-a no painel depois do teste. O caso de
paciente sem cadastro usa o CPF `52998224725`, que não deve existir no banco.
