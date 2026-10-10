param([int]$Seconds = 75)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$folder = Join-Path $root 'desktop-server/data/diagnostics'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$log = Join-Path $folder 'pairing-trace.log'
$capture = Join-Path $folder 'pairing-trace.etl'
$filter = 'QareebPairing8765'
$started = $false
$added = $false
try {
    $status = (& pktmon status 2>&1 | Out-String)
    $status | Out-File -LiteralPath $log -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Cannot access packet monitor' }
    if ($status -match '(?im)^\s*(Status|State)\s*:\s*(Running|Started)' -or $status -match '(?i)Packet monitor is running') {
        throw 'An existing packet monitor session is active; leaving it untouched'
    }
    $filters = (& pktmon filter list 2>&1 | Out-String)
    $filters | Out-File -LiteralPath $log -Append -Encoding utf8
    if ($filters -match '(?m)^\s*\d+\s+') { throw 'Existing filters found; leaving them untouched' }
    & pktmon filter add $filter -d IPv4 -t TCP -i 10.10.11.9 10.10.10.124 -p 8765 2>&1 | Out-File -LiteralPath $log -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Filter creation failed' }
    $added = $true
    # Capture only Ethernet/IP/TCP headers, never application text or pairing tokens.
    & pktmon start --capture --pkt-size 54 --file-name $capture --file-size 8 2>&1 | Out-File -LiteralPath $log -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Packet capture failed to start' }
    $started = $true
    'CAPTURE_READY' | Out-File -LiteralPath $log -Append -Encoding utf8
    Start-Sleep -Seconds ([Math]::Min(120, [Math]::Max(10, $Seconds)))
} catch {
    $_ | Out-File -LiteralPath $log -Append -Encoding utf8
} finally {
    if ($started) {
        & pktmon stop 2>&1 | Out-File -LiteralPath $log -Append -Encoding utf8
        & pktmon etl2txt $capture --out (Join-Path $folder 'pairing-trace.txt') --verbose 2>&1 | Out-File -LiteralPath $log -Append -Encoding utf8
    }
    if ($added) { & pktmon filter remove $filter 2>&1 | Out-File -LiteralPath $log -Append -Encoding utf8 }
    'CAPTURE_FINISHED' | Out-File -LiteralPath $log -Append -Encoding utf8
}
