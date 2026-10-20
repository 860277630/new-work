param()
$ErrorActionPreference = 'Stop'
function Get-TaskHash([string]$taskFile) {
    $taskStream = [System.IO.File]::OpenRead($taskFile)
    $taskHasher = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($taskHasher.ComputeHash($taskStream))).Replace('-', '').ToLowerInvariant() }
    finally { $taskStream.Dispose(); $taskHasher.Dispose() }
}
$taskRoot = Join-Path $PSScriptRoot '.tools'
$taskIndex = Join-Path $taskRoot 'ucrt64.db'
$taskMetadata = Join-Path $taskRoot 'msys2-index'
$taskDestination = Join-Path $taskRoot 'heic'
$taskCache = Join-Path $taskRoot 'msys2-packages'
New-Item -ItemType Directory -Path $taskMetadata,$taskDestination,$taskCache -Force | Out-Null
Invoke-WebRequest -Uri 'https://mirror.msys2.org/mingw/ucrt64/ucrt64.db' -OutFile $taskIndex -UseBasicParsing -TimeoutSec 120
& node (Join-Path $PSScriptRoot 'scripts\unzstd.js') $taskIndex ($taskIndex + '.tar')
if ($LASTEXITCODE -ne 0) { throw 'Cannot decompress MSYS2 index' }
# Only replace this script's generated metadata, after checking the absolute workspace path.
$taskResolvedMetadata = [System.IO.Path]::GetFullPath($taskMetadata)
$taskResolvedTools = [System.IO.Path]::GetFullPath($taskRoot)
if (!$taskResolvedMetadata.StartsWith($taskResolvedTools + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe metadata path' }
Remove-Item -LiteralPath $taskResolvedMetadata -Recurse -Force
New-Item -ItemType Directory -Path $taskResolvedMetadata -Force | Out-Null
& tar -xf ($taskIndex + '.tar') -C $taskMetadata
if ($LASTEXITCODE -ne 0) { throw 'Cannot extract MSYS2 package index' }
$taskPackages = @{}
$taskProviders = @{}
foreach ($taskDescription in Get-ChildItem -LiteralPath $taskMetadata -Filter desc -Recurse) {
    $taskFields = @{}
    $taskField = ''
    foreach ($taskLine in Get-Content -LiteralPath $taskDescription.FullName) {
        if ($taskLine -match '^%(.+)%$') { $taskField = $Matches[1]; $taskFields[$taskField] = @() }
        elseif ($taskLine -and $taskField) { $taskFields[$taskField] += $taskLine }
    }
    $taskPackages[$taskFields.NAME[0]] = $taskFields
    foreach ($taskProvide in $taskFields.PROVIDES) { $taskProviders[($taskProvide -replace '[<>=].*$', '')] = $taskFields.NAME[0] }
}
$taskQueue = [System.Collections.Generic.Queue[string]]::new()
$taskQueue.Enqueue('mingw-w64-ucrt-x86_64-libheif')
$taskVisited = @{}
$taskManifest = @()
while ($taskQueue.Count) {
    $taskName = $taskQueue.Dequeue() -replace '[<>=].*$', ''
    if ($taskVisited.ContainsKey($taskName)) { continue }
    $taskVisited[$taskName] = $true
    $taskPackage = $taskPackages[$taskName]
    if (!$taskPackage -and $taskProviders.ContainsKey($taskName)) { $taskPackage = $taskPackages[$taskProviders[$taskName]] }
    if (!$taskPackage) { throw "Missing MSYS2 package: $taskName" }
    foreach ($taskDependency in $taskPackage.DEPENDS) { $taskQueue.Enqueue($taskDependency) }
    $taskArchive = Join-Path $taskCache $taskPackage.FILENAME[0]
    $taskHash = $taskPackage.SHA256SUM[0]
    if (!(Test-Path -LiteralPath $taskArchive) -or (Get-TaskHash $taskArchive) -ne $taskHash) {
        Write-Output "Downloading $taskName $($taskPackage.VERSION[0])"
        Invoke-WebRequest -Uri ('https://mirror.msys2.org/mingw/ucrt64/' + $taskPackage.FILENAME[0]) -OutFile $taskArchive -UseBasicParsing -TimeoutSec 180
    }
    if ((Get-TaskHash $taskArchive) -ne $taskHash) { throw "Checksum mismatch: $taskName" }
    & node (Join-Path $PSScriptRoot 'scripts\unzstd.js') $taskArchive ($taskArchive + '.tar')
    if ($LASTEXITCODE -ne 0) { throw "Cannot decompress $taskName" }
    & tar -xf ($taskArchive + '.tar') -C $taskDestination
    if ($LASTEXITCODE -ne 0) { throw "Cannot extract $taskName" }
    $taskManifest += @{ name = [string]$taskPackage.NAME[0]; version = [string]$taskPackage.VERSION[0]; sha256 = [string]$taskHash }
}
$taskManifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskDestination 'manifest.json') -Encoding UTF8
& (Join-Path $taskDestination 'ucrt64\bin\heif-enc.exe') --list-encoders
if ($LASTEXITCODE -ne 0) { throw 'HEIC encoder verification failed' }
Write-Output 'HEIC tools ready. No system installation or PATH changes were made.'
