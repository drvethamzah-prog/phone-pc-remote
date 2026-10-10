param(
    [int]$IntervalSeconds = 30,
    [switch]$AllowDisplaySleep,
    [string]$HeartbeatFile = ""
)

$signature = @'
using System;
using System.Runtime.InteropServices;

public static class KeepAwakeNative {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint SetThreadExecutionState(uint esFlags);
}
'@

Add-Type -TypeDefinition $signature

$ES_CONTINUOUS = [uint32]"0x80000000"
$ES_SYSTEM_REQUIRED = [uint32]"0x00000001"
$ES_DISPLAY_REQUIRED = [uint32]"0x00000002"

$flags = $ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED
if (-not $AllowDisplaySleep) {
    $flags = $flags -bor $ES_DISPLAY_REQUIRED
}

try {
    while ($true) {
        [void][KeepAwakeNative]::SetThreadExecutionState($flags)
        if ($HeartbeatFile) {
            Set-Content -LiteralPath $HeartbeatFile -Value "awake $PID $(Get-Date -Format o)"
        }
        Start-Sleep -Seconds $IntervalSeconds
    }
}
finally {
    [void][KeepAwakeNative]::SetThreadExecutionState($ES_CONTINUOUS)
}
