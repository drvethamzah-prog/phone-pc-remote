Set shell = CreateObject("WScript.Shell")
command = "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\developings\phone-pc-remote\tools\keep-awake.ps1 -IntervalSeconds 30 -HeartbeatFile C:\developings\phone-pc-remote\tools\keep-awake.heartbeat.txt"
shell.Run command, 0, False
