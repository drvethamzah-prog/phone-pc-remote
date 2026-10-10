param([ValidateSet('Add', 'Persist', 'Rollback')][string]$Action = 'Add')
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$folder = Join-Path $root 'desktop-server/data/diagnostics'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$log = Join-Path $folder 'direct-phone-route.log'
$marker = Join-Path $folder 'direct-phone-route.json'
$prefix = '10.10.11.9/32'
try {
    $address = Get-NetIPAddress -AddressFamily IPv4 -IPAddress '10.10.10.124'
    if ($address.InterfaceAlias -ne 'Ethernet') { throw 'Unexpected interface; refusing network change' }
    $existing = Get-NetRoute -DestinationPrefix $prefix -ErrorAction SilentlyContinue
    if ($Action -eq 'Rollback') {
        if (-not (Test-Path -LiteralPath $marker)) { throw 'No record of an agent-created route' }
        $record = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
        if ($record.InterfaceIndex -ne $address.InterfaceIndex -or $record.DestinationPrefix -ne $prefix) { throw 'Route record mismatch' }
        $owned = $existing | Where-Object { $_.InterfaceIndex -eq $record.InterfaceIndex -and $_.NextHop -eq '0.0.0.0' -and $_.RouteMetric -eq 5 }
        if ($record.Store -eq 'PersistentStore') {
            Get-NetRoute -DestinationPrefix $prefix -PolicyStore PersistentStore -ErrorAction SilentlyContinue |
                Where-Object { $_.InterfaceIndex -eq $record.InterfaceIndex -and $_.NextHop -eq '0.0.0.0' -and $_.RouteMetric -eq 5 } |
                Remove-NetRoute -Confirm:$false
        }
        if ($owned) { $owned | Remove-NetRoute -Confirm:$false }
        'ROLLED_BACK' | Out-File -LiteralPath $log -Encoding utf8
        exit
    }
    $neighbor = Get-NetNeighbor -InterfaceIndex $address.InterfaceIndex -IPAddress '10.10.11.9'
    if ($neighbor.LinkLayerAddress -ne 'B6-88-3D-AA-DC-ED') { throw 'Phone neighbor changed; refusing network change' }
    if ($Action -eq 'Persist') {
        if (-not (Test-Path -LiteralPath $marker)) { throw 'No record of an agent-created route' }
        $record = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
        if ($record.InterfaceIndex -ne $address.InterfaceIndex -or $record.DestinationPrefix -ne $prefix) { throw 'Route record mismatch' }
        if (-not $existing -or @($existing | Where-Object { $_.InterfaceIndex -ne $address.InterfaceIndex -or $_.NextHop -ne '0.0.0.0' -or $_.RouteMetric -ne 5 }).Count) { throw 'Active route mismatch' }
        $saved = Get-NetRoute -DestinationPrefix $prefix -PolicyStore PersistentStore -ErrorAction SilentlyContinue
        if ($saved) {
            if ($record.Store -ne 'PersistentStore') { throw 'Unowned persistent route; refusing change' }
        } else {
            & route.exe -p ADD 10.10.11.9 MASK 255.255.255.255 0.0.0.0 METRIC 5 IF $address.InterfaceIndex | Out-File -LiteralPath $log -Encoding utf8
            if ($LASTEXITCODE -ne 0) { throw 'Persistent route command failed; active route left untouched' }
            $verified = Get-NetRoute -DestinationPrefix $prefix -PolicyStore PersistentStore -ErrorAction SilentlyContinue
            if (-not $verified) { throw 'Persistent route could not be verified' }
        }
        $record.Store = 'PersistentStore'
        $record | ConvertTo-Json | Out-File -LiteralPath $marker -Encoding utf8
        Get-NetRoute -DestinationPrefix $prefix -PolicyStore PersistentStore | Format-List DestinationPrefix,InterfaceIndex,NextHop,RouteMetric | Out-File -LiteralPath $log -Encoding utf8
        'PERSISTENT_ROUTE_READY' | Out-File -LiteralPath $log -Append -Encoding utf8
        exit
    }
    if ($existing) { throw 'A host route already exists; leaving it untouched' }
    New-NetRoute -DestinationPrefix $prefix -InterfaceIndex $address.InterfaceIndex -NextHop '0.0.0.0' -RouteMetric 5 -PolicyStore ActiveStore | Out-Null
    @{DestinationPrefix=$prefix; InterfaceIndex=$address.InterfaceIndex; RouteMetric=5; Store='ActiveStore'} | ConvertTo-Json | Out-File -LiteralPath $marker -Encoding utf8
    Get-NetRoute -DestinationPrefix $prefix | Format-List DestinationPrefix,InterfaceIndex,NextHop,RouteMetric | Out-File -LiteralPath $log -Encoding utf8
    'ROUTE_READY' | Out-File -LiteralPath $log -Append -Encoding utf8
} catch {
    $_ | Out-File -LiteralPath $log -Append -Encoding utf8
    exit 1
}
