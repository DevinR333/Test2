# Sets up the decompile tools for the Walkabout port (Windows):
#   Java 21, .NET 8 SDK, Git  ->  Il2CppDumper (Unity 6.3 / metadata v39 branch)  ->  runs it on the game
#   -> downloads Ghidra 12.1.4. Everything goes in C:\decomp. Safe to run again.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = 'C:\decomp'
$so   = 'C:\tt_struct\lib\arm64-v8a\libil2cpp.so'
$meta = 'C:\tt_struct\assets\bin\Data\Managed\Metadata\global-metadata.dat'
function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

if (-not (Test-Path $so) -or -not (Test-Path $meta)) {
    Write-Host "Can't find the game's code files:" -ForegroundColor Red
    Write-Host "  $so`n  $meta"
    Write-Host "Make sure base.apk was extracted to C:\tt_struct (step 2 earlier)."
    return
}
New-Item -ItemType Directory -Force $root | Out-Null

Step 'Installing Java 21, .NET 8 SDK and Git (already-installed ones are skipped)'
foreach ($id in 'EclipseAdoptium.Temurin.21.JDK', 'Microsoft.DotNet.SDK.8', 'Git.Git') {
    winget install --id $id -e --silent --accept-source-agreements --accept-package-agreements | Out-Host
}
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')

Step 'Building Il2CppDumper (Unity 6.3 version)'
$src = Join-Path $root 'Il2CppDumper-src'
if (-not (Test-Path $src)) { git clone -b v39 --depth 1 https://github.com/roytu/Il2CppDumper $src }
dotnet build (Join-Path $src 'Il2CppDumper\Il2CppDumper.csproj') -c Release | Out-Host
$exe = Get-ChildItem (Join-Path $src 'Il2CppDumper\bin\Release') -Recurse -Filter Il2CppDumper.exe |
    Sort-Object { $_.FullName -match 'net\d' } -Descending | Select-Object -First 1
if (-not $exe) { Write-Host 'Il2CppDumper failed to build. Copy the messages above and send them to Claude.' -ForegroundColor Red; return }

# Don't wait for a key press at the end.
$cfgPath = Join-Path $exe.DirectoryName 'config.json'
if (Test-Path $cfgPath) {
    $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json
    $cfg.RequireAnyKey = $false
    $cfg | ConvertTo-Json | Set-Content $cfgPath
}

Step 'Reading the game code (names, addresses, structures)'
$out = Join-Path $root 'il2cpp_out'
New-Item -ItemType Directory -Force $out | Out-Null
$env:DOTNET_ROLL_FORWARD = 'Major'
& $exe.FullName $so $meta $out | Out-Host
Copy-Item (Join-Path $src 'Il2CppDumper\ghidra_with_struct.py') $out -Force -ErrorAction SilentlyContinue
Copy-Item (Join-Path $src 'Il2CppDumper\ghidra.py') $out -Force -ErrorAction SilentlyContinue
Copy-Item $so $root -Force
if (-not (Test-Path (Join-Path $out 'script.json'))) {
    Write-Host 'Il2CppDumper did not produce script.json. Copy the messages above and send them to Claude.' -ForegroundColor Red
    return
}

Step 'Downloading Ghidra 12.1.4'
$ghidraDir = Get-ChildItem $root -Directory -Filter 'ghidra_*_PUBLIC' | Select-Object -First 1
if (-not $ghidraDir) {
    $rel = Invoke-RestMethod 'https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/tags/Ghidra_12.1.4_build' -Headers @{ 'User-Agent' = 'setup' }
    $asset = $rel.assets | Where-Object { $_.name -like 'ghidra_*_PUBLIC_*.zip' } | Select-Object -First 1
    Invoke-WebRequest $asset.browser_download_url -OutFile (Join-Path $root 'ghidra.zip')
    Expand-Archive (Join-Path $root 'ghidra.zip') $root -Force
    Remove-Item (Join-Path $root 'ghidra.zip')
    $ghidraDir = Get-ChildItem $root -Directory -Filter 'ghidra_*_PUBLIC' | Select-Object -First 1
}

Step 'Done'
Write-Host "Ghidra:          $($ghidraDir.FullName)\ghidraRun.bat"
Write-Host "Game code:       $root\libil2cpp.so"
Write-Host "Names/addresses: $out\script.json, il2cpp.h, dump.cs"
Write-Host "Ghidra script:   $out\ghidra_with_struct.py"
Start-Process explorer.exe $root
