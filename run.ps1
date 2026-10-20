param(
    [string]$JavaHome,
    [switch]$EnableDebugger,
    [ValidateRange(1024,65535)][int]$DebuggerPort = 5005,
    [ValidateRange(0,65535)][int]$ServicePort = 0
)
$ErrorActionPreference = 'Stop'
$taskServicePort = if ($ServicePort) { $ServicePort } elseif ($env:PORT) { [int]$env:PORT } else { 8088 }
if ($taskServicePort -lt 1024 -or $taskServicePort -gt 65535) { throw 'Service port must be between 1024 and 65535.' }
$taskProbeHost = if ($env:HOST -and $env:HOST -ne '0.0.0.0') { $env:HOST } else { '127.0.0.1' }
$taskProbeHostInUrl = if ($taskProbeHost.Contains(':')) { "[$taskProbeHost]" } else { $taskProbeHost }
$taskHealthUrl = "http://${taskProbeHostInUrl}:$taskServicePort/api/health"
$taskHealth = $null
try { $taskHealth = Invoke-RestMethod -Uri $taskHealthUrl -TimeoutSec 2 -ErrorAction Stop } catch { }
$taskIsFactory = $taskHealth -and $taskHealth.status -eq 'ok' -and (
    $taskHealth.application -eq 'wechat-format-factory' -or (
        $taskHealth.maxFileBytes -eq 20971520 -and $taskHealth.maxPdfPages -eq 20 -and
        $taskHealth.expiresHours -eq 24 -and $taskHealth.authMode -in @('development', 'wechat')
    )
)
if ($taskIsFactory) {
    Write-Output "Format factory is already running: $taskHealthUrl"
    Write-Output 'Use the existing backend. Stop it before rebuilding or changing startup settings.'
    if ($EnableDebugger) { Write-Output 'Debugger settings of the existing process are unchanged.' }
    return
}
$taskJavac = if ($JavaHome) { Join-Path $JavaHome 'bin\javac.exe' } else { (Get-Command javac -ErrorAction Stop).Source }
$taskJava = Join-Path (Split-Path -Parent $taskJavac) 'java.exe'
if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'build\classes\formatfactory\Server.class')) -or !(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'build\classes\web\index.html'))) { & (Join-Path $PSScriptRoot 'build.ps1') -JavaHome $JavaHome }
$taskClasspath = @((Join-Path $PSScriptRoot 'build\classes'), (Join-Path $PSScriptRoot '.tools\pdfbox-app-3.0.7.jar'), (Join-Path $PSScriptRoot '.tools\gson-2.13.2.jar'), (Join-Path $PSScriptRoot '.tools\onnxruntime-1.23.2.jar')) -join ';'
$taskJavaArguments = @('-Xmx512m', '-Djava.awt.headless=true')
if ($EnableDebugger) {
    $taskJavaArguments += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$DebuggerPort"
    Write-Output "IDEA Remote JVM Debug: 127.0.0.1:$DebuggerPort"
}
$taskJavaArguments += @('-cp', $taskClasspath, 'formatfactory.Server')
Push-Location -LiteralPath $PSScriptRoot
$taskPreviousPort = $env:PORT
try {
    $env:PORT = [string]$taskServicePort
    & $taskJava @taskJavaArguments
    if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }
}
finally { $env:PORT = $taskPreviousPort; Pop-Location }
