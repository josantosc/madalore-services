param(
    [switch]$Relatorio
)

$ErrorActionPreference = "Continue"

Set-Location (Split-Path -Parent $PSScriptRoot)

py scripts/publicar_dialogflow.py --check-manifest
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

mvn test
$codigo = $LASTEXITCODE

if ($Relatorio) {
    # Abre o relatorio Allure no navegador; Ctrl+C encerra o servidor local.
    mvn -q allure:serve
}

exit $codigo
