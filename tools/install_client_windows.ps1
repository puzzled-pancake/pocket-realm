# Pocket Realm client installer - Windows companion
#
# Verifies, extracts, and diagnoses the WoW 1.12.1.5875 client archives in a
# staging folder (default: the folder containing this script's target tree,
# C:\Wow clients). The in-app installer handles .zip/.7z/.rar directly; this
# script is the fallback for archives the app cannot open (encrypted,
# multi-volume, RAR4-solid) and the pre-transfer verifier that ranks every
# source before you copy ~5 GB to a handheld.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\install_client_windows.ps1
#   powershell ... -StagingRoot "C:\Wow clients" -InstallRoot "C:\Wow clients\Installed"
#   powershell ... -RankOnly        # verdict table only, extracts nothing
#   powershell ... -Source <path>   # install one specific archive/folder
param(
    [string]$StagingRoot = "C:\Wow clients",
    [string]$InstallRoot = "",
    [string]$Source = "",
    [switch]$RankOnly
)
$ErrorActionPreference = 'Stop'

# --- 7-Zip discovery -----------------------------------------------------------
$sevenZip = @(
    "$env:ProgramFiles\7-Zip\7z.exe",
    "${env:ProgramFiles(x86)}\7-Zip\7z.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $sevenZip) { Write-Error "7-Zip not found. Install it from https://7-zip.org (needed for RAR and as the extractor)." }

# --- helpers --------------------------------------------------------------------
function Get-ArchiveFormat([string]$File) {
    # FileStream: ReadAllBytes chokes on archives larger than 2 GiB.
    $stream = [System.IO.File]::OpenRead($File)
    try {
        $chunk = New-Object byte[] 16
        [void]$stream.Read($chunk, 0, 16)
    } finally { $stream.Close() }
    $bytes = $chunk
    if ($bytes[0] -eq 0x50 -and $bytes[1] -eq 0x4b) { return 'zip' }
    if ($bytes[0] -eq 0x37 -and $bytes[1] -eq 0x7a -and $bytes[2] -eq 0xbc -and $bytes[3] -eq 0xaf) { return '7z' }
    if ($bytes[0] -eq 0x52 -and $bytes[1] -eq 0x61 -and $bytes[2] -eq 0x72 -and $bytes[3] -eq 0x21) {
        if ($bytes[6] -eq 0x00 -and $bytes[7] -ne 0x01) { return 'rar4' } else { return 'rar5' }
    }
    return 'unknown'
}

function Get-Slug([string]$Name) {
    $slug = ($Name -replace '[^A-Za-z0-9_.-]', '_').Trim('._-')
    if ($slug -match '^[-.]') { $slug = "_$slug" }   # never hand 7z a leading dash
    if ($slug.Length -gt 80) { $slug = $slug.Substring(0, 80) }
    return $slug
}

function Get-ArchiveListing([string]$File) {
    # Array of lines (NOT Out-String): -match then filters line-by-line.
    # Array-argument invocation: archive-derived names can never become switches.
    & $sevenZip l -slt -- $File 2>$null
}

function Test-InstallerPayload($Listing) {
    ($Listing -match '^Path = .*setup\.exe$') -and ($Listing -match '^Path = .*setup-.*\.bin$')
}

function Get-ClientRoot([string[]]$EntryNames) {
    # The directory that directly contains BOTH WoW.exe and Data\ (wrapper included).
    $candidates = $EntryNames | Where-Object { $_ -match '(^|\\)(?i:WoW\.exe)$' -and ($_ -split '\\').Count -le 2 }
    $roots = foreach ($candidate in $candidates) {
        $parts = $candidate -split '\\'
        if ($parts.Count -eq 1) { '' } else { $parts[0] }
    }
    $roots = $roots | Select-Object -Unique
    $rooted = @($roots | Where-Object {
        $prefix = if ($_) { "$_\" } else { '' }
        @($EntryNames | Where-Object { $_ -like "$($prefix)Data" -or $_ -like "$($prefix)Data\*" }).Count -gt 0
    })
    if ($rooted.Count -eq 1) { return $rooted[0] }
    return $null
}

function Get-Realmlist([string]$ExtractedRoot) {
    $file = Join-Path $ExtractedRoot 'realmlist.wtf'
    if (Test-Path $file) {
        $line = Get-Content $file | Where-Object { $_ -match '^\s*set realmlist' } | Select-Object -First 1
        if ($line) { return ($line -replace '^\s*set realmlist\s*', '').Trim() }
    }
    return $null
}

# --- identity verification on an extracted client --------------------------------
$EXPECTED_EXE_BYTES = 4775986
function Test-ClientIdentity([string]$Root, [string]$Name) {
    $findings = @()
    $exe = Join-Path $Root 'WoW.exe'
    if (-not (Test-Path $exe)) { return @{ Ok = $false; Findings = @('WoW.exe missing') } }
    $exeSize = (Get-Item $exe).Length
    if ($exeSize -ne $EXPECTED_EXE_BYTES) { $findings += "WoW.exe is $exeSize bytes (expected $EXPECTED_EXE_BYTES for 1.12.1.5875)" }
    $version = (Get-Item $exe).VersionInfo.FileVersion
    if ($version -notlike '1.12.1.5875*') { $findings += "WoW.exe version is '$version' (expected 1.12.1.5875)" }
    $data = Join-Path $Root 'Data'
    if (-not (Test-Path $data)) { $findings += 'Data folder missing' }
    else {
        foreach ($mpq in @('base.MPQ','dbc.MPQ','fonts.MPQ','interface.MPQ','misc.MPQ','model.MPQ','sound.MPQ','speech.MPQ','terrain.MPQ','texture.MPQ','wmo.MPQ')) {
            if (-not (Test-Path (Join-Path $data $mpq))) { $findings += "Data\$mpq missing" }
        }
    }
    $hack = Join-Path $Root '!1.8 Hack'
    if (Test-Path $hack) { $findings += 'contains the "!1.8 Hack" folder (Pocket Realm excludes it automatically)' }
    return @{ Ok = ($findings.Count -eq 0); Findings = $findings }
}

# --- rank every source ------------------------------------------------------------
$archives = Get-ChildItem -Path $StagingRoot -Recurse -File -Include *.zip,*.7z,*.rar -ErrorAction SilentlyContinue
$verdicts = foreach ($archive in $archives) {
    $format = Get-ArchiveFormat $archive.FullName
    $listing = if ($format -in 'zip','7z','rar4','rar5') { Get-ArchiveListing $archive.FullName } else { '' }
    $entryNames = @($listing | Where-Object { $_ -match '^Path = ' } | ForEach-Object { $_ -replace '^Path = ', '' })
    $solid = $listing -match '^Solid = \+$'
    $encrypted = $listing -match '^Encrypted = \+$'
    $verdict = if (Test-InstallerPayload $listing) {
        'REJECTED  - Blizzard setup installer, not a client (in-app: VAL-12)'
    } elseif ($null -eq (Get-ClientRoot $entryNames)) {
        'REJECTED  - no WoW.exe + Data client inside'
    } elseif ($encrypted) {
        'REJECTED  - password-protected (in-app: VAL-13); re-pack without a password'
    } elseif ($format -eq 'rar4' -and $solid) {
        'FALLBACK  - solid RAR4; extract here with this script, then import the folder'
    } elseif ($format -eq 'rar4' -or $format -eq 'rar5') {
        "OK       - imports in-app ($format)"
    } else {
        "OK       - imports in-app ($format)"
    }
    [pscustomobject]@{ Archive = $archive.FullName; SizeGB = [math]::Round($archive.Length / 1e9, 2); Format = $format; Verdict = $verdict }
}

# already-extracted clients in the staging tree (folder-import path)
$extracted = Get-ChildItem -Path $StagingRoot -Recurse -Directory -ErrorAction SilentlyContinue |
    Where-Object { (Test-Path (Join-Path $_.FullName 'WoW.exe')) -and (Test-Path (Join-Path $_.FullName 'Data')) }
foreach ($client in $extracted) {
    $identity = Test-ClientIdentity $client.FullName $client.Name
    $verdicts += [pscustomobject]@{
        Archive = $client.FullName; SizeGB = '-'; Format = 'extracted'
        Verdict = if ($identity.Ok) { 'OK       - already extracted, folder-import path' } else { "WARN     - $($identity.Findings -join '; ')" }
    }
}

Write-Host "Pocket Realm client sources in $StagingRoot`n"
$verdicts | Sort-Object Verdict, Archive | Format-Table -AutoSize -Wrap

if ($RankOnly) { return }

# --- install one source ------------------------------------------------------------
$target = if ($Source) {
    if (Test-Path $Source -PathType Leaf) { Get-Item $Source } else { Write-Error "Source not found: $Source" }
} else {
    $ok = $verdicts | Where-Object { $_.Verdict -like 'OK*' -and $_.Format -ne 'extracted' } | Sort-Object SizeGB -Descending
    if (-not $ok) { Write-Error "No importable client archive found; see the verdict table above." }
    Write-Host "No -Source given. Best candidate: $($ok[0].Archive)`n"
    $ok[0]
}
if (-not $InstallRoot) { $InstallRoot = Join-Path $StagingRoot 'Installed' }

$format = if ($target.PSObject.Properties['Format']) { $target.Format } else { Get-ArchiveFormat $target.Archive }
$slug = Get-Slug ([IO.Path]::GetFileNameWithoutExtension($target.Archive))
$destination = Join-Path $InstallRoot $slug
if (Test-Path $destination) {
    Write-Host "Install dir exists: $destination`nRemoving it first (no silent merges)." -ForegroundColor Yellow
    Remove-Item -Recurse -Force $destination
}
New-Item -ItemType Directory -Path $destination -Force | Out-Null

Write-Host "Extracting $($target.Archive) -> $destination with 7-Zip..."
& $sevenZip x -y "-o$destination" -- $target.Archive | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Error "7-Zip failed (exit $LASTEXITCODE)." }

# the extraction may leave a wrapper folder - rebase to the client root
$listing = Get-ArchiveListing $target.Archive
$entryNames = @($listing -split "`r?`n" | Where-Object { $_ -match '^Path = ' } | ForEach-Object { $_ -replace '^Path = ', '' })
$root = Get-ClientRoot $entryNames
$clientRoot = if ($root) { Join-Path $destination $root } else { $destination }

$identity = Test-ClientIdentity $clientRoot $slug
$realmlist = Get-Realmlist $clientRoot
Write-Host ""
if ($identity.Ok) {
    Write-Host "INSTALLED: $clientRoot" -ForegroundColor Green
    Write-Host "Identity: WoW 1.12.1.5875 verified (WoW.exe size + version, 11 base MPQs present)."
} else {
    Write-Host "EXTRACTED with findings: $clientRoot" -ForegroundColor Yellow
    $identity.Findings | ForEach-Object { Write-Host "  - $_" }
}
if ($realmlist) {
    Write-Host "Current realm target: $realmlist (informational - Pocket Realm replaces realmlist.wtf with its own local realm at every launch)."
}
Write-Host ""
Write-Host "Next: copy the client folder (or the archive itself, if it ranked OK) to the device and import it in Pocket Realm (Settings > Setup > Game files and import)."
