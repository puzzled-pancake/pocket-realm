# Pocket Realm client installer - Windows companion
#
# Verifies, extracts, and diagnoses a WoW client archive in a staging folder
# (default: the folder containing this script's target tree, E:\Wow clients
# -- relocated off C: 2026-10-08, plan section 4 W0.2 / section 7 E-drive rule).
# The in-app installer handles .zip/.7z/.rar directly; this script is the
# fallback for archives the app cannot open (encrypted, multi-volume,
# RAR4-solid) and the pre-transfer verifier that ranks every source before
# you copy ~5 GB to a handheld.
#
# The identity verified (exe size, version resource, base MPQ set) is
# per-flavor S5.1-registered data resolved from schemas/flavor.json — the
# same manifest tools/flavor_layout.resolve() keys the build lanes by — so
# staging a newly registered flavor is a -Flavor argument, never a code edit
# (F12 host fixture, S5 item 8). The default is the vanilla lane and keeps
# its historical checks byte-for-byte.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\install_client_windows.ps1
#   powershell ... -StagingRoot "E:\Wow clients" -InstallRoot "E:\Wow clients\Installed"
#   powershell ... -RankOnly        # verdict table only, extracts nothing
#   powershell ... -Source <path>   # install one specific archive/folder
#   powershell ... -Flavor tbc      # verify against another registered flavor
param(
    [string]$StagingRoot = "E:\Wow clients",
    [string]$InstallRoot = "",
    [string]$Source = "",
    [string]$Flavor = "vanilla",
    [switch]$RankOnly
)
$ErrorActionPreference = 'Stop'

# --- flavor identity (S5.1 registration, schemas/flavor.json) --------------------
$flavorManifestPath = Join-Path $PSScriptRoot '..\schemas\flavor.json'
if (-not (Test-Path $flavorManifestPath)) { Write-Error "flavor manifest not found: $flavorManifestPath (the -Flavor identity source)" }
$flavorManifest = Get-Content $flavorManifestPath -Raw | ConvertFrom-Json
$flavorBlock = @($flavorManifest.flavors) | Where-Object { $_.id -eq $Flavor }
if (-not $flavorBlock) {
    $valid = ((@($flavorManifest.flavors) | ForEach-Object { $_.id }) -join ', ')
    Write-Error "unknown flavor '$Flavor' - valid flavors: $valid (mirrors tools/flavor_layout.resolve; fail-closed, never a silent vanilla default)"
}
$flavorBlock = $flavorBlock[0]
$EXPECTED_EXE_BYTES = $flavorBlock.client_binary.known_exe_size_bytes
$EXPECTED_VERSION = $flavorBlock.version_resource
# Lowercase paths relative to Data\ with forward slashes (mpq_set.required
# convention); Test-Path is case-insensitive and accepts the separators.
$REQUIRED_MPQS = @($flavorBlock.mpq_set.required)

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
# $EXPECTED_EXE_BYTES / $EXPECTED_VERSION / $REQUIRED_MPQS come from the
# flavor block resolved above (vanilla defaults keep the historical checks).
function Test-ClientIdentity([string]$Root, [string]$Name) {
    $findings = @()
    $exe = Join-Path $Root 'WoW.exe'
    if (-not (Test-Path $exe)) { return @{ Ok = $false; Findings = @('WoW.exe missing') } }
    $exeSize = (Get-Item $exe).Length
    if ($exeSize -ne $EXPECTED_EXE_BYTES) { $findings += "WoW.exe is $exeSize bytes (expected $EXPECTED_EXE_BYTES for $EXPECTED_VERSION)" }
    $rawVersion = "$((Get-Item $exe).VersionInfo.FileVersion)"
    # The genuine client stamps FileVersion in comma-separated form, which
    # the dot pattern below can never match. Normalize (trim, commas -> dots)
    # BEFORE the match; the byte-size check above is the pristine acceptance
    # path and stays untouched. The finding keeps quoting the raw on-disk
    # string for diagnosis.
    $version = $rawVersion.Trim() -replace '\s*,\s*', '.'
    if ($version -notlike "$EXPECTED_VERSION*") { $findings += "WoW.exe version is '$rawVersion' (expected $EXPECTED_VERSION)" }
    $data = Join-Path $Root 'Data'
    if (-not (Test-Path $data)) { $findings += 'Data folder missing' }
    else {
        foreach ($mpq in $REQUIRED_MPQS) {
            if (-not (Test-Path (Join-Path $data $mpq))) { $findings += "Data\$($mpq -replace '/', '\') missing" }
        }
    }
    $hack = Join-Path $Root '!1.8 Hack'
    if (Test-Path $hack) { $findings += 'contains the "!1.8 Hack" folder (Pocket Realm excludes it automatically)' }
    return @{ Ok = ($findings.Count -eq 0); Findings = $findings }
}

# --- rank every source ------------------------------------------------------------
$archives = Get-ChildItem -Path $StagingRoot -Recurse -File -Include *.zip,*.7z,*.rar -ErrorAction SilentlyContinue
# @(...) guarantees an array even when $archives is empty (or a single hit):
# the += below must append to an array, not turn a $null/scalar into
# [pscustomobject] (second += would then die on 'op_Addition').
$verdicts = @(foreach ($archive in $archives) {
    $format = Get-ArchiveFormat $archive.FullName
    $listing = if ($format -in 'zip','7z','rar4','rar5') { Get-ArchiveListing $archive.FullName } else { '' }
    $entryNames = @($listing | Where-Object { $_ -match '^Path = ' } | ForEach-Object { $_ -replace '^Path = ', '' })
    $solid = $listing -match '^Solid = \+$'
    $encrypted = $listing -match '^Encrypted = \+$'
    # Rejections the app would also hit (encrypted, solid RAR4) outrank the
    # installer-lane verdict; an installer payload is only "OK" when the app
    # can actually unpack it on device.
    $verdict = if ($encrypted) {
        'REJECTED  - password-protected (in-app: VAL-13); re-pack without a password'
    } elseif ($format -eq 'rar4' -and $solid) {
        'FALLBACK  - solid RAR4; extract here with this script, then import the folder'
    } elseif (Test-InstallerPayload $listing) {
        'OK       - original installer payload; the Pocket Realm app unpacks it on device (in-app: installer lane)'
    } elseif ($null -eq (Get-ClientRoot $entryNames)) {
        'REJECTED  - no WoW.exe + Data client inside'
    } else {
        "OK       - imports in-app ($format)"
    }
    [pscustomobject]@{ Archive = $archive.FullName; SizeGB = [math]::Round($archive.Length / 1e9, 2); Format = $format; Verdict = $verdict }
})

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
if ($Source -and (Test-Path $Source -PathType Container)) {
    # Folder -Source: the extracted-client lane - verify in place, extract nothing.
    $identity = Test-ClientIdentity $Source $Source
    if ($identity.Ok) {
        Write-Host "OK       - $Source already extracted, folder-import path" -ForegroundColor Green
    } else {
        Write-Host "WARN     - $Source already extracted, folder-import path:" -ForegroundColor Yellow
        $identity.Findings | ForEach-Object { Write-Host "  - $_" }
    }
    return
}
$target = if ($Source) {
    # Normalize into the verdict-row shape the auto-pick produces: FileInfo has
    # neither Archive nor Format, so the install lane below would otherwise
    # hand Get-ArchiveFormat $null ('Empty path name is not legal').
    if (Test-Path $Source -PathType Leaf) {
        [pscustomobject]@{ Archive = (Get-Item $Source).FullName; Format = (Get-ArchiveFormat $Source) }
    } else { Write-Error "Source not found: $Source" }
} else {
    # Installer payloads are in-app only (7-Zip cannot read Inno installers),
    # so the PC-side auto-pick skips them.
    $ok = $verdicts | Where-Object { $_.Verdict -like 'OK*' -and $_.Format -ne 'extracted' -and $_.Verdict -notmatch 'installer lane' } | Sort-Object SizeGB -Descending
    if (-not $ok) { Write-Error "No importable client archive found; see the verdict table above." }
    Write-Host "No -Source given. Best candidate: $($ok[0].Archive)`n"
    $ok[0]
}
if (-not $InstallRoot) { $InstallRoot = Join-Path $StagingRoot 'Installed' }

$format = if ($target.PSObject.Properties['Format']) { $target.Format } else { Get-ArchiveFormat $target.Archive }
$slug = Get-Slug ([IO.Path]::GetFileNameWithoutExtension($target.Archive))
# A basename with no [A-Za-z0-9_.-] at all slugs to '' - Join-Path then returns
# $InstallRoot itself, and the Remove-Item below would wipe every installed
# client instead of just one slug dir. Fall back to a fixed name.
if (-not $slug) {
    Write-Host "Archive name carries no usable characters; installing as 'client'." -ForegroundColor Yellow
    $slug = 'client'
}
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
    Write-Host "Identity: WoW $EXPECTED_VERSION verified for flavor '$Flavor' (WoW.exe size + version, $($REQUIRED_MPQS.Count) base MPQs present)."
} else {
    Write-Host "EXTRACTED with findings: $clientRoot" -ForegroundColor Yellow
    $identity.Findings | ForEach-Object { Write-Host "  - $_" }
}
if ($realmlist) {
    Write-Host "Current realm target: $realmlist (informational - Pocket Realm replaces realmlist.wtf with its own local realm at every launch)."
}
Write-Host ""
Write-Host "Next: copy the client folder (or the archive itself, if it ranked OK) to the device and import it in Pocket Realm (Settings > Setup > Game files and import)."
