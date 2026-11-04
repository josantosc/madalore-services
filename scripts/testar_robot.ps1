param(
    [ValidateSet("frases", "chatwoot", "todos")]
    [string]$Suite = "frases",
    [switch]$Visivel,
    [switch]$DryRun
)

$ErrorActionPreference = "Continue"
$raiz = Split-Path -Parent $PSScriptRoot
$robot = Join-Path $raiz "tests\robot"
$python = Join-Path $robot ".venv\Scripts\python.exe"
$env:PLAYWRIGHT_BROWSERS_PATH = "0"

if (-not (Test-Path $python)) {
    Write-Host "Criando ambiente virtual do Robot Framework..."
    py -m venv (Join-Path $robot ".venv")
    & $python -m pip install -q -r (Join-Path $robot "requirements.txt")
    & $python -m Browser.entry init chromium
}

$envFile = Join-Path $raiz ".env"
if (Test-Path $envFile) {
    foreach ($linha in Get-Content $envFile) {
        $texto = $linha.Trim()
        if (-not $texto -or $texto.StartsWith("#") -or -not $texto.Contains("=")) { continue }
        $chave, $valor = $texto.Split("=", 2)
        $chave = $chave.Trim()
        if ($chave -in @("DIALOGFLOW_CREDENTIALS", "DIALOGFLOW_PROJECT_ID", "CHATWOOT_BASE_URL", "CHATWOOT_API_TOKEN", "CHATWOOT_ACCOUNT_ID", "CHATWOOT_WEBSITE_TOKEN")) {
            [Environment]::SetEnvironmentVariable($chave, $valor.Trim())
        }
    }
}

if ($Suite -ne "frases" -and -not $DryRun -and -not $env:CHATWOOT_WEBSITE_TOKEN -and $env:CHATWOOT_API_TOKEN) {
    $base = if ($env:CHATWOOT_BASE_URL) { $env:CHATWOOT_BASE_URL.TrimEnd("/") } else { "http://192.168.0.79" }
    $conta = if ($env:CHATWOOT_ACCOUNT_ID) { $env:CHATWOOT_ACCOUNT_ID } else { "2" }
    try {
        $resposta = Invoke-RestMethod -Uri "$base/api/v1/accounts/$conta/inboxes" -Headers @{ api_access_token = $env:CHATWOOT_API_TOKEN } -TimeoutSec 15
        $site = $resposta.payload | Where-Object { $_.channel_type -eq "Channel::WebWidget" } | Select-Object -First 1
        if ($site) {
            $env:CHATWOOT_WEBSITE_TOKEN = $site.website_token
            Write-Host "Widget do Chatwoot: caixa '$($site.name)' (conta $conta)"
        } else {
            Write-Host "Nenhuma caixa Site encontrada na conta $conta do Chatwoot."
        }
    } catch {
        Write-Host "Nao foi possivel consultar as caixas do Chatwoot: $($_.Exception.Message)"
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401) {
            Write-Host "O token do bot nao lista caixas. Defina CHATWOOT_WEBSITE_TOKEN no .env (Caixas de entrada > Site > Configuracao > websiteToken)."
        }
    }
}

$alvos = switch ($Suite) {
    "frases" { @("dialogflow") }
    "chatwoot" { @("chatwoot") }
    "todos" { @("dialogflow", "chatwoot") }
}

$argumentos = @("-m", "robot", "--outputdir", "results", "--name", "Mandalore")
if ($Visivel) { $argumentos += @("--variable", "HEADLESS:False") }
if ($DryRun) { $argumentos += "--dryrun" }
$argumentos += $alvos

Push-Location $robot
try {
    & $python @argumentos
    $codigo = $LASTEXITCODE
} finally {
    Pop-Location
}

Write-Host "Relatorio: $robot\results\report.html"
exit $codigo
