#Requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$dropoff = Join-Path $root '_dropoff'
$reports = Join-Path $dropoff 'reports'
$state = Join-Path $dropoff 'state'
$run = Join-Path $root 'run'
$stage = Join-Path $state ('160_compat_collect_' + [Guid]::NewGuid().ToString('N'))
$bundle = Join-Path $reports '160_steamward_compat_runtime_bundle.zip'

New-Item -ItemType Directory -Path $stage -Force | Out-Null
New-Item -ItemType Directory -Path $reports -Force | Out-Null

function Copy-IfExists160 {
    param([string]$Source,[string]$DestinationName)
    if (Test-Path -LiteralPath $Source -PathType Leaf) {
        Copy-Item -LiteralPath $Source -Destination (Join-Path $stage $DestinationName) -Force
    }
}

try {
    # Potato reports from the just-finished run.
    $potatoStage = Join-Path $stage 'potato-reports'
    New-Item -ItemType Directory -Path $potatoStage -Force | Out-Null
    if (Test-Path -LiteralPath $reports -PathType Container) {
        Get-ChildItem -LiteralPath $reports -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -ne '160_steamward_compat_runtime_bundle.zip' } |
            ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $potatoStage -Force
            }
    }

    # NeoForge/Minecraft logs.
    Copy-IfExists160 -Source (Join-Path $run 'logs\latest.log') -DestinationName 'latest.log'
    Copy-IfExists160 -Source (Join-Path $run 'logs\debug.log') -DestinationName 'debug.log'
    Copy-IfExists160 -Source (Join-Path $state 'compat-165-registry-probe-runtime.txt') -DestinationName 'compat-165-registry-probe-runtime.txt'

    $crashStage = Join-Path $stage 'crash-reports'
    New-Item -ItemType Directory -Path $crashStage -Force | Out-Null
    $crashDir = Join-Path $run 'crash-reports'
    if (Test-Path -LiteralPath $crashDir -PathType Container) {
        Get-ChildItem -LiteralPath $crashDir -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 5 |
            ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $crashStage -Force
            }
    }

    $nativeStage = Join-Path $stage 'native-crashes'
    New-Item -ItemType Directory -Path $nativeStage -Force | Out-Null
    Get-ChildItem -LiteralPath $run -File -Filter 'hs_err_pid*.log' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 5 |
        ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $nativeStage -Force
        }

    # Exact mod inventory from the active IntelliJ run/mods directory.
    $inventory = New-Object 'System.Collections.Generic.List[string]'
    $inventory.Add('"file","sha256","bytes"') | Out-Null
    $mods = Join-Path $run 'mods'
    if (Test-Path -LiteralPath $mods -PathType Container) {
        Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' |
            Sort-Object Name |
            ForEach-Object {
                $sha = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
                $inventory.Add(
                    '"' + ($_.Name -replace '"','""') + '","' +
                    $sha + '","' + $_.Length + '"'
                ) | Out-Null
            }
    }
    [IO.File]::WriteAllText(
        (Join-Path $stage 'run_mods_inventory.csv'),
        ($inventory.ToArray() -join [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )

    # Focused compatibility error scan. The raw logs are still included.
    $scan = New-Object 'System.Collections.Generic.List[string]'
    $latest = Join-Path $run 'logs\latest.log'
    if (Test-Path -LiteralPath $latest -PathType Leaf) {
        $text = [IO.File]::ReadAllText($latest)
        foreach ($pattern in @(
            'GL_OUT_OF_MEMORY',
            'EXCEPTION_ACCESS_VIOLATION',
            'igvk64\.dll',
            'MixinApplyError',
            'MixinTransformerError',
            'NoSuchMethodError',
            'NoSuchFieldError',
            'ClassNotFoundException',
            'NoClassDefFoundError',
            'ModLoadingException',
            'Some intrusive holders were not registered',
            'Potato/Compat165',
            'UNREGISTERED_INTRUSIVE',
            'Failed to load',
            'Incompatible',
            '\[.*?/ERROR\]',
            '\[.*?/FATAL\]'
        )) {
            $count = [regex]::Matches(
                $text,
                $pattern,
                [Text.RegularExpressions.RegexOptions]::IgnoreCase
            ).Count
            $scan.Add($pattern + '=' + $count) | Out-Null
        }
    } else {
        $scan.Add('latest.log=missing') | Out-Null
    }
    [IO.File]::WriteAllText(
        (Join-Path $stage 'compat_error_scan.txt'),
        ($scan.ToArray() -join [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )

    if (Test-Path -LiteralPath $bundle -PathType Leaf) {
        Remove-Item -LiteralPath $bundle -Force
    }
    Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $bundle -CompressionLevel Optimal -Force

    Write-Host ''
    Write-Host 'COMPAT REPORT PASS' -ForegroundColor Green
    Write-Host ('Bundle: ' + $bundle)
} finally {
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
}
