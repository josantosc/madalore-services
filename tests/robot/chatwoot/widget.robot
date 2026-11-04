*** Settings ***
Documentation     Ponta a ponta no widget de site do Chatwoot:
...               navegador -> Chatwoot -> Mandalore -> Dialogflow -> backend.
...               Requer Chatwoot, Mandalore e o túnel do webhook em execução.
...               Nenhum caso confirma agendamento.
Library           Browser    timeout=20s
Library           Process
Suite Setup       Preparar Página Do Widget
Suite Teardown    Encerrar Página Do Widget
Test Setup        Abrir Conversa Nova
Test Teardown     Close Context
Test Tags         chatwoot    e2e


*** Variables ***
${CHATWOOT_BASE_URL}        %{CHATWOOT_BASE_URL=http://192.168.0.79}
${CHATWOOT_WEBSITE_TOKEN}   %{CHATWOOT_WEBSITE_TOKEN=}
${HEADLESS}                 ${TRUE}
${PORTA_SITE}               8765
${RESPOSTA_TIMEOUT}         30s
${CPF_SEM_CADASTRO}         52998224725
${FRAME}                    iframe#chatwoot_live_chat_widget >>>
${INICIAR}                  :text-matches("Iniciar conversa|Start Conversation", "i")


*** Test Cases ***
Saudação apresenta o menu
    Usuário Envia    oi
    Bot Responde     Marcar consulta

Marcar consulta pede o CPF
    Usuário Envia    oi
    Bot Responde     Marcar consulta
    Usuário Envia    marcar consulta
    Bot Responde     CPF

Paciente sem cadastro recebe a opção de agendar assim mesmo
    Usuário Envia    oi
    Bot Responde     Marcar consulta
    Usuário Envia    1
    Bot Responde     CPF
    Usuário Envia    ${CPF_SEM_CADASTRO} 19/10/1988
    Bot Responde     Agendar sem cadastro

Pedido de atendente transfere para a equipe
    [Documentation]    Abre a conversa para atendimento humano no Chatwoot.
    Usuário Envia    quero falar com atendente
    Bot Responde     alguém da equipe


*** Keywords ***
Preparar Página Do Widget
    Skip If    not $CHATWOOT_WEBSITE_TOKEN
    ...    Defina CHATWOOT_API_TOKEN ou CHATWOOT_WEBSITE_TOKEN no .env; suíte do widget ignorada.
    ${python}=    Evaluate    sys.executable    modules=sys
    Start Process    ${python}    -m    http.server    ${PORTA_SITE}
    ...    --bind    127.0.0.1    --directory    ${CURDIR}/site
    ...    alias=site
    New Browser    chromium    headless=${HEADLESS}

Encerrar Página Do Widget
    Run Keyword And Ignore Error    Close Browser    ALL
    Terminate All Processes    kill=True

Abrir Conversa Nova
    New Context    locale=pt-BR
    ...    recordVideo={'dir': '${OUTPUT DIR}/videos'}
    ${base}=     Evaluate    urllib.parse.quote($CHATWOOT_BASE_URL, safe='')    modules=urllib.parse
    New Page     http://127.0.0.1:${PORTA_SITE}/index.html?baseUrl=${base}&token=${CHATWOOT_WEBSITE_TOKEN}
    Click        .woot-widget-bubble:not(.woot--close)
    Wait For Elements State    ${FRAME} textarea, ${INICIAR} >> nth=0    visible
    Wait Until Keyword Succeeds    30s    2s    Campo De Mensagem Deve Estar Visível

Campo De Mensagem Deve Estar Visível
    ${links}=    Get Element Count    ${FRAME} ${INICIAR} >> visible=true
    IF    ${links} > 0
        Click    ${FRAME} ${INICIAR} >> visible=true >> nth=0
    END
    Wait For Elements State    ${FRAME} textarea    visible    timeout=3s

Usuário Envia
    [Arguments]    ${texto}
    Fill Text      ${FRAME} textarea    ${texto}
    Press Keys     ${FRAME} textarea    Enter

Bot Responde
    [Arguments]    ${trecho}
    Wait For Elements State    ${FRAME} text=${trecho} >> nth=-1
    ...    visible    timeout=${RESPOSTA_TIMEOUT}
    Take Screenshot
