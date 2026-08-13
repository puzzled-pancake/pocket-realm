param(
    [ValidateRange(10, 300)]
    [int] $IntervalSeconds = 20
)

$modelToken = "model:Retroid_Pocket_6"
while ($true) {
    $deviceLine = adb devices -l 2>$null |
        Where-Object { $_ -match "\sdevice\s" -and $_ -like "*$modelToken*" } |
        Select-Object -First 1
    if ($deviceLine) {
        $serial = ($deviceLine -split "\s+")[0]
        adb -s $serial shell getprop sys.boot_completed *> $null
    }
    Start-Sleep -Seconds $IntervalSeconds
}
