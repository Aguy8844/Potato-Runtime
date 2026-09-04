#Requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$mods = Join-Path $root 'run\mods'
$manifest = Join-Path $PSScriptRoot 'steamward-0.5.6-client-manifest.json'
$quarantineRoot = Join-Path $root '_dropoff\state\compat-quarantine'
$manifestSha256 = 'A434B9E1E4B15A8C0ED78645F0C8820653B0E50AB550839CECD90EEC9501AF92'

function Get-ShaRestore167 {
    param([string]$Path,[string]$Algorithm)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing required file: $Path"
    }

    return (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash.ToUpperInvariant()
}

if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
    throw "Missing compatibility manifest: $manifest"
}

if ((Get-ShaRestore167 -Path $manifest -Algorithm 'SHA256') -ne $manifestSha256) {
    throw 'Compatibility manifest generation mismatch.'
}

$data = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
$expected = @{}

foreach ($item in @($data.clientMods)) {
    $name = [string]$item.file

    if ($expected.ContainsKey($name)) {
        throw "Duplicate compatibility manifest filename: $name"
    }

    $expected[$name] = ([string]$item.sha512).ToUpperInvariant()
}

if ($expected.Count -ne 156) {
    throw "Unexpected Steamward compatibility manifest shape: $($expected.Count) client jars."
}

if (-not (Test-Path -LiteralPath $mods -PathType Container)) {
    Write-Host 'run\mods is already absent.'
    exit 0
}

$activeDirs = @(Get-ChildItem -LiteralPath $mods -Directory -ErrorAction SilentlyContinue)

if ($activeDirs.Count -gt 0) {
    throw 'Refusing restore: unexpected subdirectory exists in run\mods.'
}

$active = @(Get-ChildItem -LiteralPath $mods -File)
$activeNames = @{}

foreach ($file in $active) {
    if (-not $expected.ContainsKey($file.Name)) {
        throw "Refusing restore: unexpected file in run\mods: $($file.Name)"
    }

    if ($activeNames.ContainsKey($file.Name)) {
        throw "Refusing restore: duplicate active filename: $($file.Name)"
    }

    $actual = Get-ShaRestore167 -Path $file.FullName -Algorithm 'SHA512'

    if ($actual -ne $expected[$file.Name]) {
        throw "Refusing restore: active compatibility jar changed: $($file.Name)"
    }

    $activeNames[$file.Name] = $true
}

$quarantined = @()

if (Test-Path -LiteralPath $quarantineRoot -PathType Container) {
    $quarantineDirs = @(Get-ChildItem -LiteralPath $quarantineRoot -Directory -ErrorAction SilentlyContinue)

    if ($quarantineDirs.Count -gt 0) {
        throw 'Refusing restore: unexpected subdirectory exists in compatibility quarantine.'
    }

    $quarantined = @(Get-ChildItem -LiteralPath $quarantineRoot -File)

    foreach ($file in $quarantined) {
        if (-not $expected.ContainsKey($file.Name)) {
            throw "Refusing restore: quarantine contains a file outside the authoritative manifest: $($file.Name)"
        }

        if ($activeNames.ContainsKey($file.Name)) {
            throw "Refusing restore: jar exists both active and quarantined: $($file.Name)"
        }

        $actual = Get-ShaRestore167 -Path $file.FullName -Algorithm 'SHA512'

        if ($actual -ne $expected[$file.Name]) {
            throw "Refusing restore: quarantined jar changed: $($file.Name)"
        }
    }
}

if (($active.Count + $quarantined.Count) -ne 156) {
    throw (
        'Refusing restore: active + quarantined profile does not reconstruct the ' +
        "authoritative 156 jars. active=$($active.Count) quarantine=$($quarantined.Count)"
    )
}

foreach ($entry in $expected.GetEnumerator()) {
    $name = [string]$entry.Key
    $activePath = Join-Path $mods $name
    $quarantinePath = Join-Path $quarantineRoot $name
    $hasActive = Test-Path -LiteralPath $activePath -PathType Leaf
    $hasQuarantine = Test-Path -LiteralPath $quarantinePath -PathType Leaf

    if ($hasActive -eq $hasQuarantine) {
        throw "Refusing restore: expected exactly one active/quarantined copy of $name"
    }
}

foreach ($file in $quarantined) {
    $target = Join-Path $mods $file.Name
    [IO.File]::Copy($file.FullName,$target,$false)

    if ((Get-ShaRestore167 -Path $target -Algorithm 'SHA512') -ne $expected[$file.Name]) {
        [IO.File]::Delete($target)
        throw "Refusing restore: failed to reconstruct exact jar: $($file.Name)"
    }
}

$full = @(Get-ChildItem -LiteralPath $mods -File)

if ($full.Count -ne 156) {
    throw "Refusing restore: reconstructed profile count is $($full.Count), expected 156."
}

foreach ($file in $full) {
    if (-not $expected.ContainsKey($file.Name)) {
        throw "Refusing restore: unexpected file after reconstruction: $($file.Name)"
    }

    if ((Get-ShaRestore167 -Path $file.FullName -Algorithm 'SHA512') -ne $expected[$file.Name]) {
        throw "Refusing restore: reconstructed file hash mismatch: $($file.Name)"
    }
}

Get-ChildItem -LiteralPath $mods -Force |
    Remove-Item -Recurse -Force

foreach ($file in $quarantined) {
    if (Test-Path -LiteralPath $file.FullName -PathType Leaf) {
        Remove-Item -LiteralPath $file.FullName -Force
    }
}

Write-Host 'Steamward compatibility jars removed. run\mods is empty again.' -ForegroundColor Green
