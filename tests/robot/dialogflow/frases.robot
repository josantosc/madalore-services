*** Settings ***
Documentation     Reconhecimento de frases no agente Dialogflow publicado.
...               Não chama o webhook do Mandalore e não grava agendamentos.
Library           ../libraries/DialogflowLibrary.py
Test Setup        Iniciar Nova Conversa
Test Tags         dialogflow


*** Test Cases ***
Saudação apresenta o menu
    Usuário Envia           oi
    Intent Deve Ser         texto.saudacao
    Resposta Deve Conter    Marcar consulta

Escolher marcar consulta pede o CPF
    Usuário Envia           oi
    Intent Deve Ser         texto.saudacao
    Usuário Envia           marcar consulta
    Intent Deve Ser         texto.coleta.nascimento
    Resposta Deve Conter    CPF

Responder 1 no menu pede o CPF
    Usuário Envia           olá
    Intent Deve Ser         texto.saudacao
    Usuário Envia           1
    Intent Deve Ser         texto.coleta.nascimento
    Resposta Deve Conter    CPF

CPF reconhecido pede a data de nascimento sem chamar o webhook
    Usuário Envia           oi
    Usuário Envia           marcar consulta
    Usuário Envia           00936398302
    Intent Deve Ser         acao.paciente.identificar
    Resposta Deve Conter    data de nascimento

Começar de novo reinicia a conversa
    Usuário Envia           oi
    Usuário Envia           começar de novo
    Intent Deve Ser         texto.conversa.reiniciar
    Resposta Deve Conter    nova conversa

Pedido de atendente gera transbordo
    [Template]    Frase Deve Gerar Transbordo
    quero falar com atendente
    falar com uma pessoa
    atendente

Frase sem sentido cai no fallback
    Usuário Envia           abacaxi voador azul
    Intent Deve Ser         texto.fallback
    Resposta Deve Conter    Não entendi


*** Keywords ***
Frase Deve Gerar Transbordo
    [Arguments]    ${frase}
    Iniciar Nova Conversa
    Usuário Envia           ${frase}
    Intent Deve Ser         transbordo.atendente
    Resposta Deve Conter    alguém da equipe
