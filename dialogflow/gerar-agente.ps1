# Gera o zip-import do Dialogflow ES (v2) com entidades de sistema.
# Sessao, texto e webhook seguem os limites da referencia ES.

$root = $PSScriptRoot
$intentsDir = Join-Path $root "intents"
$entitiesDir = Join-Path $root "entities"
New-Item -ItemType Directory -Force -Path $intentsDir, $entitiesDir | Out-Null

Add-Type -AssemblyName System.Web.Extensions
$serializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
$serializer.MaxJsonLength = 20000000
$utf8 = New-Object System.Text.UTF8Encoding $false

function Write-Json($path, $value) {
    $json = $serializer.Serialize($value)
    [System.IO.File]::WriteAllText($path, $json, $utf8)
}

function New-Id { [guid]::NewGuid().ToString() }

function Parte([string]$text, [string]$alias, [string]$meta) {
    $item = @{ text = $text; userDefined = $false }
    if ($meta) {
        $item.alias = $alias
        $item.meta = $meta
        $item.userDefined = $true
    }
    return $item
}

function Frase($partes) {
    return @{
        id = (New-Id)
        data = @($partes)
        isTemplate = $false
        count = 0
        updated = 0
    }
}

function Parametro([string]$name, [string]$type, [bool]$required, [string]$prompt) {
    $item = @{
        id = (New-Id)
        required = $required
        dataType = $type
        name = $name
        value = ('$' + $name)
        isList = $false
    }
    if ($prompt) {
        $item.prompts = @(@{ lang = "pt-br"; value = $prompt })
    }
    return $item
}

Write-Json (Join-Path $root "agent.json") @{
    description = "Agendamento de consulta"
    language = "pt-br"
    displayName = "agendamento-consulta"
    disableInteractionLogs = $true
    disableStackdriverLogs = $true
    defaultTimezone = "America/Sao_Paulo"
    webhook = @{
        url = ""
        username = ""
        headers = @{}
        available = $true
        useForDomains = $false
        cloudFunctionsEnabled = $false
        cloudFunctionsInitialized = $false
    }
    isPrivate = $true
    mlMinConfidence = 0.3
    supportedLanguages = @()
    onePlatformApiVersion = "v2"
    analyzeQueryTextSentiment = $false
    enabledKnowledgeBaseNames = @()
    knowledgeServiceConfidenceAdjustment = 0.0
    dialogBuilderMode = $false
}

Write-Json (Join-Path $root "package.json") @{ version = "1.0.0" }

function Write-Entity([string]$name, [bool]$regexp, $entries) {
    Write-Json (Join-Path $entitiesDir "$name.json") @{
        id = (New-Id)
        name = $name
        isOverridable = $true
        isEnum = $false
        isRegexp = $regexp
        automatedExpansion = $false
        allowFuzzyExtraction = $false
    }
    Write-Json (Join-Path $entitiesDir "${name}_entries_pt-br.json") @($entries)
}

Write-Entity "periodo" $false @(
    @{ value = "manha"; synonyms = @("manhã", "manha", "de manhã", "cedo") }
    @{ value = "tarde"; synonyms = @("tarde", "à tarde", "de tarde") }
    @{ value = "tanto_faz"; synonyms = @("tanto faz", "qualquer", "indiferente") }
)
Write-Entity "confirmacao" $false @(
    @{ value = "sim"; synonyms = @("sim", "isso", "pode", "pode marcar", "confirmo", "quero") }
    @{ value = "nao"; synonyms = @("não", "nao", "não quero", "outro horário", "troca") }
)
Write-Entity "navegacao" $false @(
    @{ value = "voltar"; synonyms = @("voltar", "volta", "passo anterior") }
)
Write-Entity "cpf" $true @(
    @{ value = "\d{3}\.?\d{3}\.?\d{3}-?\d{2}"; synonyms = @("\d{3}\.?\d{3}\.?\d{3}-?\d{2}") }
)

function Write-Intent {
    param(
        [string]$name,
        [string]$speech,
        [bool]$webhook,
        [bool]$fallback,
        [string[]]$events,
        [string[]]$contexts,
        $phrases,
        $parameters,
        $affected
    )
    $eventList = @()
    foreach ($eventName in $events) { $eventList += @{ name = $eventName } }
    $contextList = @()
    foreach ($contextName in $contexts) { $contextList += $contextName }
    $paramList = @()
    if ($parameters) { foreach ($parameter in $parameters) { $paramList += $parameter } }
    $affectedList = @()
    if ($affected) { foreach ($item in $affected) { $affectedList += $item } }

    Write-Json (Join-Path $intentsDir "$name.json") @{
        id = (New-Id)
        name = $name
        auto = $true
        contexts = $contextList
        responses = @(
            @{
                resetContexts = $false
                action = ""
                affectedContexts = $affectedList
                parameters = $paramList
                messages = @(@{ type = 0; lang = "pt-br"; speech = @($speech) })
                defaultResponsePlatforms = @{}
                speech = @()
            }
        )
        priority = 500000
        webhookUsed = $webhook
        webhookForSlotFilling = $false
        fallbackIntent = $fallback
        events = $eventList
    }

    if ($phrases) {
        $says = @()
        foreach ($phrase in $phrases) { $says += $phrase }
        Write-Json (Join-Path $intentsDir "${name}_usersays_pt-br.json") $says
    }
}

$menu = "Olá. Posso marcar um atendimento ou cuidar de um agendamento que você já tem.`n`n1. Consulta`n2. Exame`n3. Terapias especializadas`n4. Inserção de DIU`n5. Linhas de cuidado`n6. Ver, remarcar ou cancelar`n`nPode digitar o número ou o nome."

Write-Intent "texto.saudacao" $menu $false $false @("WELCOME") @() @(
    (Frase @(Parte "oi" $null $null))
    (Frase @(Parte "olá" $null $null))
    (Frase @(Parte "bom dia" $null $null))
    (Frase @(Parte "quero marcar uma consulta" $null $null))
) @() @()

Write-Intent "texto.fallback" "Não entendi. Pode reformular ou pedir um atendente?" $false $true @() @() $null @() @()

Write-Intent "texto.coleta.nascimento" "Para achar o cadastro, qual a data de nascimento? Pode ser dia/mês/ano, por exemplo 19/10/1988." $false $false @() @() @(
    (Frase @(Parte "minha data de nascimento" $null $null))
    (Frase @(
        (Parte "nasci em " $null $null),
        (Parte "19/10/1988" "data_nascimento" "@sys.date")
    ))
) @(Parametro "data_nascimento" "@sys.date" $false $null) @()

Write-Intent "texto.coleta.cpf" "Obrigado. Qual o CPF de quem vai ser atendido?" $false $false @() @() @(
    (Frase @(
        (Parte "cpf " $null $null),
        (Parte "123.456.789-01" "cpf" "@cpf")
    ))
) @(Parametro "cpf" "@cpf" $false $null) @()

Write-Intent "texto.coleta.periodo" "Para qual dia? Pode ser uma data, como 20/07/2026, ou esta semana." $false $false @() @("especialidade") @(
    (Frase @(Parte "manhã" "periodo" "@periodo"))
    (Frase @(Parte "tarde" "periodo" "@periodo"))
    (Frase @(Parte "tanto faz" "periodo" "@periodo"))
) @(Parametro "periodo" "@periodo" $true "Qual período fica melhor? 1. Manhã 2. Tarde 3. Tanto faz") @(@{ name = "periodo"; lifespan = 10 })

Write-Intent "texto.consulta.marcar_outro" "Qual convênio você quer usar?" $false $false @() @("agendamento_futuro") @(
    (Frase @(Parte "marcar outro" $null $null))
    (Frase @(Parte "quero outra consulta" $null $null))
) @() @()

Write-Intent "texto.consulta.seguir_especialidade" "Qual período fica melhor?`n`n1. Manhã`n2. Tarde`n3. Tanto faz`n`nPode digitar o número ou voltar." $false $false @() @("profissional") @(
    (Frase @(Parte "sim" "confirmacao" "@confirmacao"))
    (Frase @(Parte "seguir" $null $null))
    (Frase @(Parte "pode" "confirmacao" "@confirmacao"))
) @() @(@{ name = "especialidade"; lifespan = 15 })

Write-Intent "texto.navegacao.voltar" "Voltei um passo. Pode responder de novo." $false $false @() @() @(
    (Frase @(Parte "voltar" "navegacao" "@navegacao"))
    (Frase @(Parte "volta" "navegacao" "@navegacao"))
    (Frase @(Parte "passo anterior" "navegacao" "@navegacao"))
) @(Parametro "navegacao" "@navegacao" $false $null) @()

Write-Intent "transbordo.atendente" "Vou chamar alguém da equipe. O histórico desta conversa fica com a pessoa." $false $false @() @() @(
    (Frase @(Parte "atendente" $null $null))
    (Frase @(Parte "quero falar com alguém" $null $null))
    (Frase @(Parte "pessoa" $null $null))
) @() @()

Write-Intent "acao.paciente.identificar" "Certo. Vou olhar o cadastro." $true $false @() @() @(
    (Frase @(
        (Parte "nasci em " $null $null),
        (Parte "19/10/1988" "data_nascimento" "@sys.date"),
        (Parte " cpf " $null $null),
        (Parte "123.456.789-01" "cpf" "@cpf")
    ))
    (Frase @(
        (Parte "19/10/1988" "data_nascimento" "@sys.date"),
        (Parte " " $null $null),
        (Parte "12345678901" "cpf" "@cpf")
    ))
) @(
    (Parametro "data_nascimento" "@sys.date" $true "Para achar o cadastro, qual a data de nascimento? Pode ser dia/mês/ano, por exemplo 19/10/1988.")
    (Parametro "cpf" "@cpf" $true "Obrigado. Qual o CPF de quem vai ser atendido?")
) @()

Write-Intent "acao.consulta.convenios" "Vou ver os convênios." $true $false @() @("paciente") @(
    (Frase @(Parte "convênio" $null $null))
    (Frase @(Parte "plano" $null $null))
) @() @()

Write-Intent "acao.consulta.profissionais" "Vou ver quem já te atendeu." $true $false @() @("paciente", "convenio") @(
    (Frase @(Parte "médico" $null $null))
    (Frase @(Parte "profissional" $null $null))
) @() @()

Write-Intent "acao.consulta.especialidades" "Vou ver as especialidades." $true $false @() @("paciente", "convenio") @(
    (Frase @(Parte "especialidade" $null $null))
) @() @()

Write-Intent "acao.consulta.escolher_lista" "Certo." $true $false @() @("lista") @(
    (Frase @(Parte "1" "escolha" "@sys.number"))
    (Frase @(Parte "ver outros" $null $null))
    (Frase @(Parte "ver mais" $null $null))
    (Frase @(Parte "outra pessoa" $null $null))
) @(Parametro "escolha" "@sys.number" $false $null) @()

Write-Intent "acao.agenda.disponibilidade" "Vou olhar os horários." $true $false @() @("paciente", "convenio", "especialidade", "periodo") @(
    (Frase @(Parte "amanhã" "data" "@sys.date"))
    (Frase @(Parte "20/07/2026" "data" "@sys.date"))
    (Frase @(Parte "esta semana" $null $null))
) @(Parametro "data" "@sys.date" $true "Para qual dia? Pode ser uma data, como 20/07/2026, ou esta semana.") @(@{ name = "dia"; lifespan = 10 })

Write-Intent "acao.agenda.meus_agendamentos" "Vou olhar seus agendamentos." $true $false @() @("paciente") @(
    (Frase @(Parte "ver" $null $null))
    (Frase @(Parte "meus agendamentos" $null $null))
    (Frase @(Parte "remarcar" $null $null))
    (Frase @(Parte "cancelar" $null $null))
) @() @()

Write-Intent "acao.agenda.escolher" "Certo." $true $false @() @("oferta") @(
    (Frase @(Parte "1" "escolha" "@sys.number"))
    (Frase @(Parte "2" "escolha" "@sys.number"))
    (Frase @(Parte "o primeiro" $null $null))
) @(Parametro "escolha" "@sys.number" $true "Digite o número do horário.") @()

Write-Intent "acao.agenda.agendar" "Vou marcar." $true $false @() @("paciente", "aguardando_confirmacao") @(
    (Frase @(Parte "sim" "confirmacao" "@confirmacao"))
    (Frase @(Parte "pode marcar" "confirmacao" "@confirmacao"))
    (Frase @(Parte "confirmo" "confirmacao" "@confirmacao"))
) @(Parametro "confirmacao" "@confirmacao" $true "Posso marcar esse horário?") @()

Write-Intent "acao.agenda.confirmar" "Certo." $true $false @() @("paciente", "agendamentos") @(
    (Frase @(Parte "confirmar" $null $null))
    (Frase @(
        (Parte "confirmar " $null $null),
        (Parte "1" "escolha" "@sys.number")
    ))
) @(Parametro "escolha" "@sys.number" $false $null) @()

Write-Intent "acao.agenda.cancelar" "Certo." $true $false @() @("paciente", "agendamentos") @(
    (Frase @(Parte "cancelar" $null $null))
    (Frase @(Parte "quero cancelar" $null $null))
    (Frase @(
        (Parte "cancelar " $null $null),
        (Parte "2" "escolha" "@sys.number")
    ))
) @(Parametro "escolha" "@sys.number" $false $null) @()

Write-Intent "acao.agenda.remarcar" "Certo." $true $false @() @("paciente", "agendamentos") @(
    (Frase @(Parte "remarcar" $null $null))
    (Frase @(Parte "trocar horário" $null $null))
    (Frase @(
        (Parte "remarcar " $null $null),
        (Parte "1" "escolha" "@sys.number")
    ))
) @(Parametro "escolha" "@sys.number" $false $null) @()

$resultados = @{
    "texto.resultado.paciente_nao_encontrado" = @{ e = "RESULTADO_PACIENTE_NAO_ENCONTRADO"; t = "Não encontrei cadastro com essa data e esse CPF. Quer tentar de novo?" }
    "texto.resultado.nao_identificado" = @{ e = "RESULTADO_NAO_IDENTIFICADO"; t = "Para seguir, preciso da data de nascimento e do CPF." }
    "texto.resultado.sucesso_identificar" = @{ e = "RESULTADO_SUCESSO_IDENTIFICAR"; t = "Encontrei o cadastro de `$nome." }
    "texto.resultado.ja_tem_agendamento" = @{ e = "RESULTADO_JA_TEM_AGENDAMENTO"; t = "`$nome, você já tem consulta em `$quando. Posso marcar outra, ver, remarcar ou cancelar." }
    "texto.resultado.lista_convenio" = @{ e = "RESULTADO_LISTA_CONVENIO"; t = "Qual convênio você quer usar?`n`$lista`n`nPode digitar o número, o nome ou voltar." }
    "texto.resultado.lista_profissional" = @{ e = "RESULTADO_LISTA_PROFISSIONAL"; t = "Quer marcar de novo com alguém que já te atendeu?`n`$lista" }
    "texto.resultado.uma_especialidade" = @{ e = "RESULTADO_UMA_ESPECIALIDADE"; t = "Com `$medico, a especialidade é `$especialidade. Sigo com ela?" }
    "texto.resultado.lista_especialidade" = @{ e = "RESULTADO_LISTA_ESPECIALIDADE"; t = "Qual especialidade você quer com `$medico?`n`$lista" }
    "texto.resultado.sucesso_lista" = @{ e = "RESULTADO_SUCESSO_LISTA"; t = "`$lista" }
    "texto.resultado.sem_horario" = @{ e = "RESULTADO_SEM_HORARIO"; t = "Não há horário de `$especialidade nesse período. Posso olhar outro período, outro dia, ou chamar alguém." }
    "texto.resultado.sem_agendamento" = @{ e = "RESULTADO_SEM_AGENDAMENTO"; t = "`$nome, você não tem consulta em aberto." }
    "texto.resultado.confirmacao_ausente" = @{ e = "RESULTADO_CONFIRMACAO_AUSENTE"; t = "`$texto" }
    "texto.resultado.horario_fora_da_oferta" = @{ e = "RESULTADO_HORARIO_FORA_DA_OFERTA"; t = "Escolha um dos horários que acabei de passar." }
    "texto.resultado.horario_indisponivel" = @{ e = "RESULTADO_HORARIO_INDISPONIVEL"; t = "Esse horário não está mais livre." }
    "texto.resultado.agendamento_nao_encontrado" = @{ e = "RESULTADO_AGENDAMENTO_NAO_ENCONTRADO"; t = "Não encontrei essa consulta." }
    "texto.resultado.sucesso_escrita" = @{ e = "RESULTADO_SUCESSO_ESCRITA"; t = "`$texto" }
    "texto.resultado.indisponivel" = @{ e = "RESULTADO_INDISPONIVEL"; t = "Não consegui consultar agora. Pode tentar de novo ou pedir um atendente." }
    "texto.resultado.periodo" = @{ e = "RESULTADO_PERIODO"; t = "Qual período fica melhor?`n`n1. Manhã`n2. Tarde`n3. Tanto faz`n`nPode digitar o número ou voltar." }
    "texto.resultado.dia" = @{ e = "RESULTADO_DIA"; t = "Para qual dia? Pode ser uma data, como 20/07/2026, ou esta semana." }
}

foreach ($nome in $resultados.Keys) {
    $item = $resultados[$nome]
    Write-Intent $nome $item.t $false $false @($item.e) @() $null @() @()
}
