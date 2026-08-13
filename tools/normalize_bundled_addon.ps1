param(
    [Parameter(Mandatory = $true)]
    [string] $SourceDirectory,

    [Parameter(Mandatory = $true)]
    [string] $OutputZip,

    [string] $RootFolder = "PocketRealmPad"
)

$source = (Resolve-Path -LiteralPath $SourceDirectory).Path
$output = [IO.Path]::GetFullPath($OutputZip)
$outputDirectory = [IO.Path]::GetDirectoryName($output)
if (-not [IO.Directory]::Exists($outputDirectory)) {
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}

Add-Type -AssemblyName System.IO.Compression

$temporary = "$output.tmp"
if ([IO.File]::Exists($temporary)) {
    [IO.File]::Delete($temporary)
}

$stream = [IO.File]::Open(
    $temporary,
    [IO.FileMode]::CreateNew,
    [IO.FileAccess]::ReadWrite,
    [IO.FileShare]::None
)
try {
    $archive = [IO.Compression.ZipArchive]::new(
        $stream,
        [IO.Compression.ZipArchiveMode]::Create,
        $true
    )
    try {
        Get-ChildItem -LiteralPath $source -Recurse -File |
            Sort-Object FullName |
            ForEach-Object {
                $relative = $_.FullName.Substring($source.Length).TrimStart('\').Replace('\', '/')
                $entry = $archive.CreateEntry(
                    "$RootFolder/$relative",
                    [IO.Compression.CompressionLevel]::Optimal
                )
                $entry.LastWriteTime = [DateTimeOffset]::new(
                    2026,
                    8,
                    11,
                    0,
                    0,
                    0,
                    [TimeSpan]::Zero
                )
                # Addons are data-only. Clearing host Unix permission bits keeps the
                # release acceptable to the fail-closed archive validator.
                $entry.ExternalAttributes = 0
                $input = [IO.File]::OpenRead($_.FullName)
                $target = $entry.Open()
                try {
                    $input.CopyTo($target)
                }
                finally {
                    $target.Dispose()
                    $input.Dispose()
                }
            }
    }
    finally {
        $archive.Dispose()
    }
}
finally {
    $stream.Dispose()
}

[IO.File]::Copy($temporary, $output, $true)
[IO.File]::Delete($temporary)
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash.ToLowerInvariant()
Write-Output "$hash  $output"
