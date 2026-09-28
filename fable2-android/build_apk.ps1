<#
.SYNOPSIS
  Builds a native Android APK of Fable 2 from YOUR OWN disc image.

.DESCRIPTION
  Everything runs on this PC; the ISO and the game code generated from it
  never leave it. Steps (each one is skipped when already done, so a failed
  run can simply be started again):

    1. Tools      - installs Git, Python, CMake, Ninja and a JDK with winget
                    if missing; downloads the Android SDK + NDK.
    2. ISO        - checks the SHA-256 and extracts default.xex, data/,
                    $SystemUpdate/ (tools/extract_xiso.py).
    3. Sources    - clones Fable-2-Recomp and its ReXGlue SDK fork at pinned
                    commits and applies the Android patches (patches/).
    4. Codegen    - downloads the official ReXGlue 0.10.0 code generator and
                    recompiles your default.xex into C++ (generated/default).
    5. APK        - cross-compiles everything for arm64 Android with Gradle
                    and writes Fable2.apk next to this script.
    6. Phone      - if a phone is connected with USB debugging, installs the
                    APK and copies the game files onto it.

.PARAMETER Iso
  Path to the Fable 2 disc image. Omit it to pick the file in a dialog.

.PARAMETER WorkDir
  Build folder (needs ~60 GB free). Keep it short: Windows path limits.

.PARAMETER NoPhone
  Skip step 6 even when a phone is connected.
#>
[CmdletBinding()]
param(
    [string]$Iso,
    [string]$WorkDir = "$env:SystemDrive\f2build",
    [switch]$NoPhone
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# ---------------------------------------------------------------------------
# Pins (what this Android port was written against)
# ---------------------------------------------------------------------------
$FableRepo    = 'https://github.com/himdo/Fable-2-Recomp.git'
$FableCommit  = '6a0d57b33b2d0f4ad1f1031c9f0082a98f8acd52'
$SdkRepo      = 'https://github.com/himdo/rexglue-sdk.git'
$SdkCommit    = '1338ec1011739c7f00f8df9b9473e34d3dd9f2df'
$IsoSha256    = '685a0d3bea9718812f17bcd155907a5359a548b6d3d8342dd2a6c944f45e35ff'
$NdkVersion   = '27.2.12479018'
$RexglueZip   = 'https://github.com/rexglue/rexglue-sdk/releases/download/v0.10.0/rexglue-sdk-0.10.0-win-amd64.zip'
$CmdlineTools = 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip'
$PackageName  = 'com.fable2.recomp'

$Here     = $PSScriptRoot
$GameDir  = Join-Path $WorkDir 'game'
$FableDir = Join-Path $WorkDir 'Fable-2-Recomp'
$SdkDir   = Join-Path $WorkDir 'rexglue-sdk'
$SdkRoot  = Join-Path $WorkDir 'android-sdk'

function Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Info($text) { Write-Host "    $text" }
function Warn($text) { Write-Host "    $text" -ForegroundColor Yellow }
function Fail($text) { Write-Host "`nERROR: $text" -ForegroundColor Red; exit 1 }

# Run a native command and stop on a non-zero exit code. Native tools (git,
# sdkmanager, ...) print progress to stderr; with 'Stop' in effect Windows
# PowerShell 5.1 would treat that as a failure, so only the exit code counts.
function Run {
    param([string]$Exe, [string[]]$Arguments, [string]$Dir = $null)
    $ErrorActionPreference = 'Continue'
    if ($Dir) { Push-Location $Dir }
    try {
        & $Exe @Arguments
        if ($LASTEXITCODE -ne 0) { Fail "'$Exe $($Arguments -join ' ')' failed (exit $LASTEXITCODE)" }
    } finally {
        if ($Dir) { Pop-Location }
    }
}

# Same stderr rule for native calls whose output or exit code is inspected.
function Native([scriptblock]$Block) {
    $ErrorActionPreference = 'Continue'
    & $Block
}

# Run a long build: keep going past the first failure, save everything to a
# log, and on failure print every distinct error together (the console
# scrollback usually only shows warnings by the time the build stops).
function Run-Build {
    param([string]$Exe, [string[]]$Arguments, [string]$Log, [string]$Dir = $null)
    $ErrorActionPreference = 'Continue'
    if ($Dir) { Push-Location $Dir }
    try {
        & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath $Log | Out-Host
        $code = $LASTEXITCODE
    } finally {
        if ($Dir) { Pop-Location }
    }
    if ($code -ne 0) {
        Write-Host "`n----- errors (full log: $Log) -----" -ForegroundColor Red
        Select-String -Path $Log -Pattern '(error:|error [A-Z]+\d+|FAILED:|fatal error|undefined (symbol|reference)|What went wrong)' |
            ForEach-Object { $_.Line.Trim() } | Select-Object -Unique -First 60 |
            ForEach-Object { Write-Host $_ -ForegroundColor Red }
        Fail "Build failed (exit $code). Send a screenshot of the red lines above, or the file $Log"
    }
}

function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User')
}

# ---------------------------------------------------------------------------
# 1. Tools
# ---------------------------------------------------------------------------
Step '1/6 Tools'

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$drive = (Get-Item $WorkDir).PSDrive
if ($drive.Free -lt 60GB) {
    Warn ("Only {0:N0} GB free on {1}: - the build needs about 60 GB." -f ($drive.Free / 1GB), $drive.Name)
}

$winget = Get-Command winget -ErrorAction SilentlyContinue
function Winget-Install([string]$Id, [string[]]$Extra = @()) {
    Native { & winget install --id $Id -e --accept-source-agreements --accept-package-agreements @Extra | Out-Host }
}
function Ensure-Tool {
    param([string]$Command, [string]$WingetId, [string[]]$ExtraPaths = @())
    foreach ($p in $ExtraPaths) {
        if ((Test-Path $p) -and -not ($env:Path -split ';' -contains $p)) { $env:Path = "$p;$env:Path" }
    }
    if (Get-Command $Command -ErrorAction SilentlyContinue) { return }
    if (-not $winget) { Fail "$Command not found and winget is unavailable. Install $WingetId manually." }
    Info "Installing $WingetId ..."
    Winget-Install $WingetId @('--silent')
    Refresh-Path
    foreach ($p in $ExtraPaths) { if (Test-Path $p) { $env:Path = "$p;$env:Path" } }
    if (-not (Get-Command $Command -ErrorAction SilentlyContinue)) {
        Fail "$Command still not found after installing $WingetId. Open a new terminal and run again."
    }
}

Ensure-Tool git     'Git.Git'
Ensure-Tool cmake   'Kitware.CMake'        @("$env:ProgramFiles\CMake\bin")
Ensure-Tool ninja   'Ninja-build.Ninja'

# The prebuilt rexglue.exe (step 4) needs the Microsoft Visual C++ runtime.
if (-not (Test-Path "$env:SystemRoot\System32\msvcp140.dll") -or
    -not (Test-Path "$env:SystemRoot\System32\vcruntime140_1.dll")) {
    if (-not $winget) { Fail 'Install the Microsoft Visual C++ Redistributable (x64) and retry.' }
    Info 'Installing the Microsoft Visual C++ runtime ...'
    Winget-Install 'Microsoft.VCRedist.2015+.x64' @('--silent')
}

# Python. "python" on a fresh Windows is often only the Microsoft Store alias
# (WindowsApps\python.exe), which opens the Store instead of running scripts.
function Find-Python {
    $candidates = @()
    $candidates += Get-Command python.exe -All -ErrorAction SilentlyContinue |
                   Where-Object { $_.Source -notlike '*\WindowsApps\*' } | ForEach-Object { $_.Source }
    $candidates += Get-ChildItem "$env:LOCALAPPDATA\Programs\Python\Python3*\python.exe",
                                 "$env:ProgramFiles\Python3*\python.exe" -ErrorAction SilentlyContinue |
                   Sort-Object FullName -Descending | ForEach-Object { $_.FullName }
    foreach ($exe in $candidates) {
        Native { & $exe -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" } | Out-Null
        if ($LASTEXITCODE -eq 0) { return $exe }
    }
    return $null
}
$Python = Find-Python
if (-not $Python) {
    if (-not $winget) { Fail 'Python 3 not found. Install it from python.org and retry.' }
    Info 'Installing Python.Python.3.12 ...'
    Winget-Install 'Python.Python.3.12' @('--silent')
    $Python = Find-Python
    if (-not $Python) { Fail 'Python 3.12 install failed. Install it from python.org and retry.' }
}
# First on PATH, ahead of the Store alias, for CMake and the build scripts.
$env:Path = "$(Split-Path -Parent $Python);$env:Path"
Info "Python: $Python"

$cmakeVersion = ((Native { & cmake --version } | Select-Object -First 1) -replace '[^0-9.]', '')
if ([version]$cmakeVersion -lt [version]'3.25') { Fail "CMake $cmakeVersion is too old (need 3.25+). Update CMake." }

# JDK 17 for Gradle.
$jdk = Get-ChildItem "$env:ProgramFiles\Microsoft" -Filter 'jdk-17*' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) {
    if (-not $winget) { Fail 'JDK 17 not found. Install Microsoft Build of OpenJDK 17.' }
    Info 'Installing Microsoft.OpenJDK.17 ...'
    Winget-Install 'Microsoft.OpenJDK.17' @('--silent')
    $jdk = Get-ChildItem "$env:ProgramFiles\Microsoft" -Filter 'jdk-17*' -Directory | Select-Object -First 1
    if (-not $jdk) { Fail 'JDK 17 install failed.' }
}
$env:JAVA_HOME = $jdk.FullName
$env:Path = "$($jdk.FullName)\bin;$env:Path"

# Android SDK + NDK (portable, inside the work dir).
$sdkmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    Info 'Downloading Android command-line tools ...'
    $zip = Join-Path $WorkDir 'cmdline-tools.zip'
    Invoke-WebRequest -Uri $CmdlineTools -OutFile $zip
    $tmp = Join-Path $WorkDir 'cmdline-tools-tmp'
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot 'cmdline-tools') | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $SdkRoot 'cmdline-tools\latest')
    Remove-Item $tmp, $zip -Recurse -Force
}
$env:ANDROID_HOME = $SdkRoot
if (-not (Test-Path (Join-Path $SdkRoot "ndk\$NdkVersion"))) {
    Info 'Installing Android platform, build tools and NDK (a few GB) ...'
    Native { (1..30 | ForEach-Object { 'y' }) | & $sdkmanager --sdk_root=$SdkRoot --licenses | Out-Null }
    Run $sdkmanager @("--sdk_root=$SdkRoot", 'platform-tools', 'platforms;android-35',
                      'build-tools;35.0.0', "ndk;$NdkVersion")
}
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
Info 'Tools ready.'

# ---------------------------------------------------------------------------
# 2. ISO
# ---------------------------------------------------------------------------
Step '2/6 Game files from your ISO'

if (-not (Test-Path (Join-Path $GameDir 'default.xex'))) {
    if (-not $Iso) {
        Add-Type -AssemblyName System.Windows.Forms
        $dlg = New-Object System.Windows.Forms.OpenFileDialog
        $dlg.Title = 'Select your Fable 2 disc image'
        $dlg.Filter = 'Xbox 360 disc image (*.iso)|*.iso|All files (*.*)|*.*'
        if ($dlg.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { Fail 'No ISO selected.' }
        $Iso = $dlg.FileName
    }
    if (-not (Test-Path $Iso)) { Fail "ISO not found: $Iso" }

    Info "Checking $Iso (SHA-256, takes a minute) ..."
    $hash = (Get-FileHash -Path $Iso -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne $IsoSha256) {
        Warn "SHA-256 is $hash"
        Warn "expected    $IsoSha256 (Fable 2 GOTY, USA/EU)."
        Warn 'The recomp was built against that exact disc; a different one will probably not run.'
        $answer = Read-Host '    Continue anyway? (y/N)'
        if ($answer -notmatch '^[yY]') { Fail 'Stopped: ISO does not match.' }
    } else {
        Info 'ISO matches.'
    }
    Run $Python @((Join-Path $Here 'tools\extract_xiso.py'), $Iso, $GameDir)
}
foreach ($needed in 'default.xex', 'data') {
    if (-not (Test-Path (Join-Path $GameDir $needed))) { Fail "$needed missing from $GameDir after extraction." }
}
Info "Game files in $GameDir"

# ---------------------------------------------------------------------------
# 3. Sources
# ---------------------------------------------------------------------------
Step '3/6 Sources'

Run git @('config', '--global', 'core.longpaths', 'true')

function Checkout-Pinned {
    param([string]$Url, [string]$Commit, [string]$Dir)
    if (-not (Test-Path (Join-Path $Dir '.git'))) {
        Run git @('-c', 'core.autocrlf=false', 'clone', '--filter=blob:none', $Url, $Dir)
    }
    # Git for Windows converts line endings to CRLF on checkout by default,
    # which makes every patch below fail to apply. Keep files exactly as
    # committed (the compilers do not care either way).
    Run git @('-C', $Dir, 'config', 'core.autocrlf', 'false')
    Run git @('-C', $Dir, 'fetch', '--quiet', 'origin', $Commit)
    # Start from a clean tree so patches apply the same way on every run.
    # Dropping the index forces files written by an earlier CRLF checkout to
    # be rewritten by the reset.
    Run git @('-C', $Dir, 'checkout', '--quiet', '--force', $Commit)
    Run git @('-C', $Dir, 'rm', '-r', '-q', '--cached', '--ignore-unmatch', '.')
    Run git @('-C', $Dir, 'reset', '--quiet', '--hard')
    Run git @('-C', $Dir, 'clean', '-fdq', '-e', 'generated/', '-e', 'out/', '-e', 'default.xex')
}

Checkout-Pinned $FableRepo $FableCommit $FableDir
Checkout-Pinned $SdkRepo $SdkCommit $SdkDir

Info 'Fetching SDK dependencies (large; first run only) ...'
# Fetch every dependency straight at the commit the recomp was built with
# (rexglue-sdk-submodule-pins.txt). Plain `git submodule update` cannot be
# used: the SDK fork records at least one commit (libmspack) that its
# upstream no longer serves, so it aborts.
# Git on Windows cannot create symbolic links without Developer Mode, so it
# checks each one out as a small text file holding the target path (which
# then fails to compile, e.g. libmspack's cabextract/mspack/*.c). Replace
# every such placeholder with a copy of what it points to.
function Resolve-Symlinks([string]$Repo) {
    $links = @(Native { & git -C $Repo ls-files -s } | Where-Object { $_ -match '^120000 ' } |
               ForEach-Object { ($_ -split "`t", 2)[1] })
    # Links can point at other links; a few passes settle chains.
    for ($pass = 0; $pass -lt 4; $pass++) {
        foreach ($rel in $links) {
            $path = Join-Path $Repo $rel
            $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            if (-not $item -or $item.PSIsContainer -or $item.LinkType -or $item.Length -gt 1024) { continue }
            $target = (Get-Content -LiteralPath $path -Raw).Trim()
            if (-not $target -or $target.Contains("`n")) { continue }
            $source = Join-Path (Split-Path -Parent $path) $target
            if (Test-Path -LiteralPath $source -PathType Leaf) {
                Copy-Item -LiteralPath $source -Destination $path -Force
            } elseif (Test-Path -LiteralPath $source -PathType Container) {
                Remove-Item -LiteralPath $path -Force
                Copy-Item -LiteralPath $source -Destination $path -Recurse -Force
            }
        }
    }
}

$pins = Get-Content (Join-Path $FableDir 'thirdparty\rexglue-sdk-submodule-pins.txt') |
        Where-Object { $_ -match '^thirdparty/' }
foreach ($line in $pins) {
    $path, $sha = $line -split '\s+'
    $sub = Join-Path $SdkDir $path
    $cur = Native { & git -C $sub rev-parse HEAD 2>$null }
    if ((Test-Path (Join-Path $sub '.git')) -and $cur -eq $sha) { Resolve-Symlinks $sub; continue }

    $url = Native { & git -C $SdkDir config -f .gitmodules --get "submodule.$path.url" }
    if (-not $url) { Fail "No URL for $path in the SDK's .gitmodules." }
    Info "  $path"
    if (-not (Test-Path (Join-Path $sub '.git'))) {
        New-Item -ItemType Directory -Force -Path $sub | Out-Null
        Run git @('-C', $sub, 'init', '--quiet')
    }
    Run git @('-C', $sub, 'config', 'core.autocrlf', 'false')
    Native { & git -C $sub remote remove origin 2>$null } | Out-Null
    Run git @('-C', $sub, 'remote', 'add', 'origin', $url)
    Run git @('-C', $sub, 'fetch', '--quiet', '--depth', '1', 'origin', $sha)
    Run git @('-C', $sub, 'checkout', '--quiet', '--force', $sha)
    Resolve-Symlinks $sub
}

Info 'Applying patches ...'
Run git @('-C', $SdkDir, 'apply', (Join-Path $FableDir 'thirdparty\sdk_mainmenu_crash_fix.patch'))
Run git @('-C', $SdkDir, 'apply', (Join-Path $Here 'patches\rexglue-sdk-android.patch'))
Run git @('-C', $FableDir, 'apply', (Join-Path $Here 'patches\fable2-android.patch'))

# SDL's Java half of the Android backend, matching the pinned SDL.
$sdlJava = Join-Path $SdkDir 'thirdparty\sdl3\android-project\app\src\main\java\org\libsdl\app'
$appJava = Join-Path $Here 'android\app\src\main\java\org\libsdl\app'
New-Item -ItemType Directory -Force -Path $appJava | Out-Null
Copy-Item (Join-Path $sdlJava '*.java') $appJava -Force

# ---------------------------------------------------------------------------
# 4. Codegen (runs on this PC)
# ---------------------------------------------------------------------------
Step '4/6 Recompiling default.xex to C++'

# Written only after codegen AND the recomp patches both succeeded.
$stamp = Join-Path $FableDir 'generated\default\android_codegen.done'
if (-not (Test-Path $stamp)) {
    # The official prebuilt ReXGlue 0.10.0 code generator - the same one the
    # recomp author's build.cmd downloads and runs. Its codegen sources and
    # templates are identical to the fork's, so its output matches the fork
    # headers the Android build compiles against. (Building the fork's own
    # rexglue.exe on Windows is not possible: its rexruntime.dll exports only
    # what the game and GPU plugin use.)
    $prebuiltDir = Join-Path $WorkDir 'rexglue-0.10.0'
    $rexglue = Get-ChildItem -Path $prebuiltDir -Filter 'rexglue.exe' -Recurse -ErrorAction SilentlyContinue |
               Select-Object -First 1 -ExpandProperty FullName
    if (-not $rexglue) {
        Info 'Downloading the ReXGlue 0.10.0 code generator ...'
        $zip = Join-Path $WorkDir 'rexglue-sdk-0.10.0-win-amd64.zip'
        Invoke-WebRequest -Uri $RexglueZip -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $prebuiltDir -Force
        Remove-Item $zip -Force
        $rexglue = Get-ChildItem -Path $prebuiltDir -Filter 'rexglue.exe' -Recurse |
                   Select-Object -First 1 -ExpandProperty FullName
        if (-not $rexglue) { Fail "rexglue.exe not found in the downloaded SDK ($prebuiltDir)." }
    }
    Info "Code generator: $rexglue"
    Copy-Item (Join-Path $GameDir 'default.xex') (Join-Path $FableDir 'default.xex') -Force
    Info 'Running codegen (several minutes) ...'
    Run $rexglue @('codegen', 'fable_2_manifest.toml') $FableDir
    Run $Python @('tools\apply_recomp_patches.py', 'generated\default') $FableDir
    Set-Content -Path $stamp -Value $FableCommit
} else {
    Info 'Already generated.'
}

# ---------------------------------------------------------------------------
# 5. APK
# ---------------------------------------------------------------------------
Step '5/6 Building the APK (first build takes a long time - the game is huge)'

$androidDir = Join-Path $Here 'android'
$cmakeDir = Split-Path -Parent (Split-Path -Parent (Get-Command cmake).Source)
$ninjaExe = (Get-Command ninja).Source
$props = @(
    "sdk.dir=$($SdkRoot -replace '\\', '\\')",
    "cmake.dir=$($cmakeDir -replace '\\', '\\')"
)
Set-Content -Path (Join-Path $androidDir 'local.properties') -Value $props -Encoding ASCII

Run-Build (Join-Path $androidDir 'gradlew.bat') @(
    'assembleRelease', '--no-daemon', '--console=plain', '--continue',
    "-Pfable2Dir=$($FableDir -replace '\\', '/')",
    "-PrexsdkDir=$($SdkDir -replace '\\', '/')",
    "-Pfable2CmakeVersion=$cmakeVersion",
    "-Pfable2Ninja=$($ninjaExe -replace '\\', '/')",
    "-Pfable2Staging=$((Join-Path $WorkDir 'cxx') -replace '\\', '/')"
) (Join-Path $WorkDir 'build-android.log') $androidDir

$apkOut = Join-Path $androidDir 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apkOut)) { Fail "Gradle finished but $apkOut is missing." }
$apk = Join-Path $Here 'Fable2.apk'
Copy-Item $apkOut $apk -Force
Info "APK: $apk"

# ---------------------------------------------------------------------------
# 6. Phone
# ---------------------------------------------------------------------------
Step '6/6 Phone'

$device = $null
if (-not $NoPhone) {
    $device = (Native { & $adb devices }) | Select-String -Pattern '\tdevice$' | Select-Object -First 1
}
if (-not $device) {
    Info 'No phone connected (or -NoPhone). To finish by hand:'
    Info "  1. Install $apk on the phone."
    Info "  2. Copy default.xex, data\ and `$SystemUpdate\ from $GameDir to"
    Info "     Android/data/$PackageName/files/ on the phone (USB file transfer)."
    Info '  3. Start "Fable II".'
    exit 0
}

Info 'Installing ...'
Run $adb @('install', '-r', $apk)
$remote = "/sdcard/Android/data/$PackageName/files"
Run $adb @('shell', 'mkdir', '-p', $remote)
Info 'Copying game files to the phone (~7 GB, several minutes) ...'
foreach ($item in 'default.xex', 'data', '$SystemUpdate') {
    $src = Join-Path $GameDir $item
    if (Test-Path $src) { Run $adb @('push', '--sync', $src, "$remote/") }
}
Write-Host "`nDone. Start 'Fable II' on your phone." -ForegroundColor Green
