param([switch]$Test, [string]$JavaHome)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskJavac = if ($JavaHome) { Join-Path $JavaHome 'bin\javac.exe' } else { (Get-Command javac -ErrorAction Stop).Source }
$taskJava = Join-Path (Split-Path -Parent $taskJavac) 'java.exe'
$taskTools = Join-Path $taskRoot '.tools'
$taskBuild = Join-Path $taskRoot 'build\classes'
New-Item -ItemType Directory -Path $taskTools,$taskBuild -Force | Out-Null
$taskDependencies = @(
    @{ Name = 'pdfbox-app-3.0.7.jar'; Url = 'https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox-app/3.0.7/pdfbox-app-3.0.7.jar' },
    @{ Name = 'gson-2.13.2.jar'; Url = 'https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar' },
    @{ Name = 'onnxruntime-1.23.2.jar'; Url = 'https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.23.2/onnxruntime-1.23.2.jar' }
)
foreach ($taskDependency in $taskDependencies) {
    $taskFile = Join-Path $taskTools $taskDependency.Name
    if (!(Test-Path -LiteralPath $taskFile)) { Invoke-WebRequest -Uri $taskDependency.Url -OutFile $taskFile -TimeoutSec 120 }
    if ((Get-Item -LiteralPath $taskFile).Length -lt 100000) { throw "Dependency incomplete: $taskFile" }
}
$taskClasspath = ($taskDependencies | ForEach-Object { Join-Path $taskTools $_.Name }) -join ';'
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'backend\src\formatfactory') -Filter '*.java' | ForEach-Object { $_.FullName })
& $taskJavac --release 17 -encoding UTF-8 -cp $taskClasspath -d $taskBuild $taskSources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$taskWebResources = Join-Path $taskBuild 'web'
New-Item -ItemType Directory -Path $taskWebResources -Force | Out-Null
Copy-Item -Path (Join-Path $taskRoot 'web\*') -Destination $taskWebResources -Force
if ($Test) {
    & $taskJavac --release 17 -encoding UTF-8 -cp "$taskBuild;$taskClasspath" -d $taskBuild (Join-Path $taskRoot 'tests\ConversionTest.java') (Join-Path $taskRoot 'tests\HttpIntegrationTest.java') (Join-Path $taskRoot 'tests\ImageFormatsTest.java') (Join-Path $taskRoot 'tests\ResourceMonitorTest.java') (Join-Path $taskRoot 'tests\IdPhotoTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & $taskJava '-Djava.awt.headless=true' -cp "$taskBuild;$taskClasspath" formatfactory.ConversionTest
    if ($LASTEXITCODE -ne 0) { throw 'Conversion tests failed' }
    & $taskJava '-Djava.awt.headless=true' -cp "$taskBuild;$taskClasspath" formatfactory.HttpIntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'HTTP integration tests failed' }
    & $taskJava '-Djava.awt.headless=true' -cp "$taskBuild;$taskClasspath" formatfactory.ImageFormatsTest
    if ($LASTEXITCODE -ne 0) { throw 'Image format tests failed' }
    & $taskJava '-Djava.awt.headless=true' -cp "$taskBuild;$taskClasspath" formatfactory.ResourceMonitorTest
    if ($LASTEXITCODE -ne 0) { throw 'Resource monitor tests failed' }
    & $taskJava '-Djava.awt.headless=true' -cp "$taskBuild;$taskClasspath" formatfactory.IdPhotoTest
    if ($LASTEXITCODE -ne 0) { throw 'ID photo tests failed' }
    & node (Join-Path $taskRoot 'tests\miniprogram.test.js')
    if ($LASTEXITCODE -ne 0) { throw 'Mini-program checks failed' }
    & node (Join-Path $taskRoot 'tests\idphoto-miniprogram.test.js')
    if ($LASTEXITCODE -ne 0) { throw 'ID photo mini-program checks failed' }
}
Write-Output 'Build complete. Run: .\run.cmd'
