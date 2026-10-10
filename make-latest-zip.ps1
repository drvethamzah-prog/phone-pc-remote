param(
    [string]$OutputName = "phone-pc-remote-latest.zip"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path -LiteralPath $PSScriptRoot
$ReleaseDir = Join-Path $ProjectRoot "releases"
$OutputPath = Join-Path $ReleaseDir $OutputName
$StageRoot = Join-Path $env:TEMP ("phone-pc-remote-package-" + [guid]::NewGuid().ToString())
$StageProject = Join-Path $StageRoot "phone-pc-remote"

New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null

if (Test-Path -LiteralPath $OutputPath) {
    Remove-Item -LiteralPath $OutputPath -Force
}

New-Item -ItemType Directory -Force -Path $StageRoot | Out-Null

$ExcludeDirs = @(
    "releases",
    "diagnostics",
    ".git",
    "node_modules",
    "build",
    "dist",
    ".gradle",
    ".gradle-local",
    ".android-sdk",
    ".android-avd",
    ".android-home",
    ".idea",
    ".vscode",
    "__pycache__"
)

$ExcludeFiles = @(
    "*.pyc",
    "*.tmp",
    "*.log",
    "trusted-devices.json",
    "local-identity.json",
    "settings.json",
    "dashboard-settings.json",
    "startup-settings.json",
    "events.log",
    "local.properties",
    ".android-commandlinetools.zip",
    ".DS_Store",
    "Thumbs.db"
)

$robocopyArgs = @(
    $ProjectRoot,
    $StageProject,
    "/E",
    "/NFL",
    "/NDL",
    "/NJH",
    "/NJS",
    "/XD"
) + $ExcludeDirs + @("/XF") + $ExcludeFiles

robocopy @robocopyArgs | Out-Null

if ($LASTEXITCODE -gt 7) {
    throw "Failed to stage project files. Robocopy exit code: $LASTEXITCODE"
}

$ApkSource = Join-Path $ReleaseDir "phone-pc-remote-android-debug.apk"
if (Test-Path -LiteralPath $ApkSource) {
    $StageApkDir = Join-Path $StageProject "android-apk"
    New-Item -ItemType Directory -Force -Path $StageApkDir | Out-Null
    Copy-Item -LiteralPath $ApkSource -Destination (Join-Path $StageApkDir "phone-pc-remote-android-debug.apk") -Force
}
Compress-Archive -Path $StageProject -DestinationPath $OutputPath -Force
$ResolvedStageRoot = [System.IO.Path]::GetFullPath($StageRoot)
$ResolvedTempRoot = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
if (-not $ResolvedStageRoot.StartsWith($ResolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    [System.IO.Path]::GetFileName($ResolvedStageRoot) -notlike 'phone-pc-remote-package-*') {
    throw "Refusing cleanup outside the package staging directory: $ResolvedStageRoot"
}
Remove-Item -LiteralPath $StageRoot -Recurse -Force

Write-Host "Created: $OutputPath"

