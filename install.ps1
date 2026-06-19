<#
.SYNOPSIS
    MapUploader quick installer (Windows / PowerShell).

.DESCRIPTION
    Run from your Minecraft server directory (the folder with server.properties):

        irm https://raw.githubusercontent.com/MarcosLorCar/MapUploader/main/install.ps1 | iex

    To pass options, download then run:

        iwr https://raw.githubusercontent.com/MarcosLorCar/MapUploader/main/install.ps1 -OutFile install.ps1
        .\install.ps1 -Mode proxy

    Modes:
      proxy  - MapUploader replaces your server jar and launches the real server as a
               child process, so one start command runs both with a shared lifecycle.
      normal - standalone web app with its own start script, run next to the server.
#>
# NOTE: no [CmdletBinding()] / [ValidateSet] attributes here on purpose — they break
# `irm ... | iex` (the attribute is applied to the variable in the caller's scope with
# an empty value and fails validation). $Mode is validated manually below instead.
param(
    [string]$Mode = '',
    [string]$Jar = '',
    [int]$Port = 8080,
    [string]$Version = 'latest',
    [switch]$NoDatapack,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$Repo = 'MarcosLorCar/MapUploader'
$Api  = "https://api.github.com/repos/$Repo"
$Raw  = "https://raw.githubusercontent.com/$Repo/main"
$SP   = 'server.properties'

# ----------------------------------------------------------------------------- helpers
function Write-Log  ($m) { Write-Host "[MapUploader] $m" -ForegroundColor Cyan }
function Write-Ok   ($m) { Write-Host "[MapUploader] $m" -ForegroundColor Green }
function Write-Warn ($m) { Write-Host "[MapUploader] $m" -ForegroundColor Yellow }
function Die        ($m) { Write-Host "[MapUploader] $m" -ForegroundColor Red; exit 1 }

function Confirm-Action ($q) {
    if ($Yes) { return $true }
    return ((Read-Host "$q [y/N]") -match '^(y|yes)$')
}

function Get-ServerProps {
    $h = @{}
    if (Test-Path $SP) {
        foreach ($line in Get-Content $SP) {
            $t = $line.Trim()
            if ($t -and -not $t.StartsWith('#') -and $t.Contains('=')) {
                $i = $t.IndexOf('='); $h[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
            }
        }
    }
    return $h
}
function Get-Prop ($props, $key, $default) {
    if ($props.ContainsKey($key) -and $props[$key]) { return $props[$key] } else { return $default }
}
function Set-Prop ($key, $val) {
    $lines = if (Test-Path $SP) { @(Get-Content $SP) } else { @() }
    $found = $false
    $out = foreach ($l in $lines) {
        if ($l -match ("^" + [regex]::Escape($key) + "=")) { $found = $true; "$key=$val" } else { $l }
    }
    if (-not $found) { $out = @($out) + "$key=$val" }
    Set-Content -Path $SP -Value $out -Encoding Ascii
}

function Get-Release {
    $headers = @{ 'User-Agent' = 'MapUploader-installer' }
    if ($Version -eq 'latest') { Invoke-RestMethod "$Api/releases/latest" -Headers $headers }
    else { Invoke-RestMethod "$Api/releases/tags/$Version" -Headers $headers }
}
function Get-AssetUrl ($pattern) {
    (Get-Release).assets |
        Where-Object { $_.name -match $pattern } |
        Select-Object -First 1 -ExpandProperty browser_download_url
}
function Save-File ($url, $out) {
    Write-Log "Downloading $(Split-Path $out -Leaf)..."
    Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
}

# ----------------------------------------------------------------------------- checks
$javaLine = try { (& java -version 2>&1)[0] } catch { Die 'Java is not installed. MapUploader needs Java 17 or newer.' }
if ($javaLine -match '(\d+)') {
    $major = [int]$Matches[1]
    if ($major -lt 17) { Die "Java 17+ is required (found: $javaLine)." }
}
if (-not (Test-Path $SP)) { Write-Warn 'No server.properties here. Run this from your server directory for auto-config.' }

$JarUrl = Get-AssetUrl '\.jar$'
if (-not $JarUrl) { Die "Could not find a .jar asset in the '$Version' release of $Repo." }

# ----------------------------------------------------------------------------- mode
if ($Mode -and $Mode -notin @('proxy', 'normal')) { Die "Invalid -Mode '$Mode' (use 'proxy' or 'normal')." }
if (-not $Mode) {
    Write-Host ''
    Write-Log "Choose an installation type (docs: https://github.com/$Repo#installation):"
    Write-Host '   1) proxy  - one command starts the server + MapUploader together (recommended)'
    Write-Host '   2) normal - standalone web app with its own start script'
    $Mode = if ((Read-Host 'Enter 1 or 2') -eq '2') { 'normal' } else { 'proxy' }
}

$props = Get-ServerProps
$level = Get-Prop $props 'level-name' 'world'

function Install-Datapack {
    if ($NoDatapack) { return }
    $dpdir = Join-Path $level 'datapacks'
    $url = Get-AssetUrl 'MapUploader\.zip$'; if (-not $url) { $url = "$Raw/MapUploader.zip" }
    New-Item -ItemType Directory -Force -Path $dpdir | Out-Null
    Save-File $url (Join-Path $dpdir 'MapUploader.zip')
    Write-Ok "Datapack installed to $dpdir\. Run /reload (or restart) to enable it."
}

# ----------------------------------------------------------------------------- normal
if ($Mode -eq 'normal') {
    New-Item -ItemType Directory -Force -Path 'mapuploader' | Out-Null
    Save-File $JarUrl 'mapuploader\mapuploader.jar'
    $rconPort = Get-Prop $props 'rcon.port' '25575'
    $rconPass = Get-Prop $props 'rcon.password' ''
    @"
# Standalone MapUploader web app launcher (generated by install.ps1).
Set-Location -Path `$PSScriptRoot
`$env:MC_WORLD_DATA_PATH = Join-Path `$PSScriptRoot "$level\data\minecraft\maps"
`$env:MC_RCON_HOST = '127.0.0.1'
`$env:MC_RCON_PORT = '$rconPort'
`$env:MC_RCON_PASSWORD = '$rconPass'
`$env:MAPUPLOADER_WEB_PORT = '$Port'
java -jar mapuploader\mapuploader.jar @args
"@ | Set-Content -Path 'start-mapuploader.ps1' -Encoding Ascii
    Install-Datapack
    Write-Host ''
    Write-Ok 'Done. Start the web app with:  .\start-mapuploader.ps1'
    if (-not $rconPass) { Write-Warn 'RCON password is empty in server.properties; in-game map delivery will fail until it is set.' }
    exit 0
}

# ----------------------------------------------------------------------------- proxy
Write-Host ''
Write-Warn 'Proxy install must run while the Minecraft server is STOPPED (the jar gets renamed).'
if (-not (Confirm-Action 'Is the server stopped and ready?')) { Die 'Stop the server, then run this again.' }

if (Get-ChildItem -File -Filter 'proxied_*.jar' -ErrorAction SilentlyContinue) {
    Die "A 'proxied_*.jar' already exists here - MapUploader looks already installed. Run uninstall-mapuploader.ps1 first."
}

# pick the server jar
if (-not $Jar) {
    $cands = @(Get-ChildItem -File -Filter '*.jar' | Where-Object { $_.Name -notlike 'proxied_*' } | Select-Object -ExpandProperty Name)
    if ($cands.Count -eq 0) { Die 'No .jar files found here. Run this from your server directory.' }
    elseif ($cands.Count -eq 1) { $Jar = $cands[0]; Write-Log "Found one server jar: $Jar" }
    else {
        Write-Log 'Multiple jars found - which one is your Minecraft server?'
        for ($i = 0; $i -lt $cands.Count; $i++) { Write-Host "   $($i+1)) $($cands[$i])" }
        $sel = [int](Read-Host 'Enter number'); $Jar = $cands[$sel - 1]
    }
}
if (-not $Jar -or -not (Test-Path $Jar)) { Die "Invalid jar selection: '$Jar'" }

# RCON: required for graceful shutdown and in-game map delivery
$rconOn = (Get-Prop $props 'enable-rcon' 'false') -eq 'true'
$rconPass = Get-Prop $props 'rcon.password' ''
if (-not $rconOn -or -not $rconPass) {
    Write-Host ''
    Write-Warn 'Proxy mode needs RCON enabled so MapUploader can stop the server safely and deliver maps.'
    Write-Host '   It requires these in server.properties:'
    Write-Host '     enable-rcon=true'
    Write-Host "     rcon.port=$(Get-Prop $props 'rcon.port' '25575')"
    Write-Host '     rcon.password=<a password>'
    if ((Test-Path $SP) -and (Confirm-Action 'Let the installer set these now (backs up server.properties, generates a random password)?')) {
        Copy-Item $SP "$SP.bak.$([int][double]::Parse((Get-Date -UFormat %s)))"
        $chars = [char[]]'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
        $pw = -join (1..20 | ForEach-Object { $chars | Get-Random })
        Set-Prop 'enable-rcon' 'true'
        Set-Prop 'rcon.port' (Get-Prop $props 'rcon.port' '25575')
        Set-Prop 'rcon.password' $pw
        Write-Ok 'RCON enabled (password written to server.properties).'
    }
    else { Die 'Aborting. Enable RCON in server.properties, then run this again.' }
}

# download to a staging file and verify it is a launcher-capable jar BEFORE touching
# the real server jar (older releases have no proxy launcher).
Save-File $JarUrl '.mapuploader.dl'
$manifest = ''
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path '.mapuploader.dl'))
    $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
    if ($entry) { $reader = New-Object System.IO.StreamReader($entry.Open()); $manifest = $reader.ReadToEnd(); $reader.Close() }
    $zip.Dispose()
} catch {}
if ($manifest -notmatch 'me\.orange\.LauncherKt') {
    Remove-Item '.mapuploader.dl' -Force -ErrorAction SilentlyContinue
    Die 'The latest release jar has no proxy launcher. Update to MapUploader v1.2.0 or newer.'
}

# swap jar identity: real server -> proxied_<name>, wrapper (staged) -> <name>
Copy-Item $Jar "$Jar.bak.$([int][double]::Parse((Get-Date -UFormat %s)))"
Move-Item $Jar "proxied_$Jar"
Move-Item '.mapuploader.dl' $Jar
Write-Ok "Installed: your start command's '-jar $Jar' now launches MapUploader, which runs 'proxied_$Jar'."

# uninstaller
@"
# Reverts the MapUploader proxy install.
Set-Location -Path `$PSScriptRoot
if (-not (Test-Path 'proxied_$Jar')) { Write-Host 'Nothing to revert (proxied_$Jar not found).'; exit 1 }
Remove-Item '$Jar' -Force
Move-Item 'proxied_$Jar' '$Jar'
Write-Host 'Reverted: $Jar is the original server jar again. (Datapack and server.properties left untouched.)'
"@ | Set-Content -Path 'uninstall-mapuploader.ps1' -Encoding Ascii

Install-Datapack
Write-Host ''
Write-Ok 'All set. Start your server the way you normally do - MapUploader comes up with it.'
Write-Log "Web UI: http://<host>:$Port/ (use /trigger UploadMap in-game for your link)."
Write-Log 'To revert:  .\uninstall-mapuploader.ps1'
