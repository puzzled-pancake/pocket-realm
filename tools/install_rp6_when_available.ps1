param(
    [Parameter(Mandatory = $true)]
    [string] $ApkPath,
    [Parameter(Mandatory = $true)]
    [string] $ExpectedSha256,
    [ValidateRange(10, 300)]
    [int] $IntervalSeconds = 15
)

$knownSerial = "adb-REDACTED-DEVICE._adb-tls-connect._tcp"
$knownModel = "model:Retroid_Pocket_6"
$workspace = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $workspace "tmp"
$logPath = Join-Path $logDirectory "rp6-one-shot-install.log"
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

function Write-InstallLog([string] $Message) {
    Add-Content -LiteralPath $logPath -Value "$(Get-Date -Format o) $Message"
}

function Read-AppHashes([string] $Serial) {
    $paths = @(
        "no_backup/user-account/account.json",
        "no_backup/client/active.json",
        "no_backup/addons/registry.json",
        "files/content/o11-server/active.json"
    )
    @($paths | ForEach-Object {
        $path = $_
        $result = & adb -s $Serial shell run-as com.pocketrealm sha256sum $path 2>&1
        "$path`t$($result -join ' ')"
    })
}

$actualSha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash
if ($actualSha256 -ne $ExpectedSha256) {
    Write-InstallLog "REFUSED: APK SHA-256 mismatch ($actualSha256)"
    exit 2
}

Write-InstallLog "Waiting for the known RP6; APK SHA-256 $actualSha256"
while ($true) {
    $deviceLine = adb devices -l 2>$null |
        Where-Object {
            $_ -match "^$([regex]::Escape($knownSerial))\s+device\s" -and
            $_ -like "*$knownModel*"
        } |
        Select-Object -First 1
    if (-not $deviceLine) {
        Start-Sleep -Seconds $IntervalSeconds
        continue
    }

    $before = Read-AppHashes $knownSerial
    Write-InstallLog "Known RP6 connected; starting data-preserving package replacement"
    $installOutput = & adb -s $knownSerial install -r $resolvedApk 2>&1
    $installExit = $LASTEXITCODE
    Write-InstallLog "adb install exit=$installExit output=$($installOutput -join ' ')"
    if ($installExit -ne 0) {
        exit $installExit
    }

    $after = Read-AppHashes $knownSerial
    $beforeText = $before -join "`n"
    $afterText = $after -join "`n"
    if ($beforeText -eq $afterText) {
        Write-InstallLog "SUCCESS: package replaced and protected app records are unchanged"
        exit 0
    }

    Write-InstallLog "WARNING: package replacement succeeded but protected-record snapshots differ"
    Write-InstallLog "BEFORE: $($before -join ' | ')"
    Write-InstallLog "AFTER: $($after -join ' | ')"
    exit 3
}
