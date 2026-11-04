$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path .env)) {
    throw "Arquivo .env ausente. Rode: py scripts/publicar_dialogflow.py"
}
Get-Content .env | ForEach-Object {
    $linha = $_.Trim()
    if ($linha.Length -eq 0 -or $linha.StartsWith("#")) { return }
    $indice = $linha.IndexOf("=")
    if ($indice -lt 1) { return }
    $nome = $linha.Substring(0, $indice).Trim()
    $valor = $linha.Substring($indice + 1).Trim()
    Set-Item -Path "Env:$nome" -Value $valor
}
mvn spring-boot:run
