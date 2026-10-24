# Windows: connects to the TV over the network, copies tvtune.sh and packages.txt
# onto it and runs one step there. Output is also saved to tvtune-<step>.log.
#
#   powershell -ExecutionPolicy Bypass -File .\run.ps1 <tv-ip> scan|apply|launcher|replace-home|restore
param(
    [Parameter(Mandatory = $true)] [string] $Ip,
    [Parameter(Mandatory = $true)]
    [ValidateSet('scan', 'apply', 'launcher', 'replace-home', 'restore')] [string] $Step
)

$env:Path = "$PSScriptRoot;$env:Path"  # also finds adb.exe copied into this folder
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Error 'adb not found: install Android platform-tools and add it to your PATH'
    exit 1
}
$target = if ($Ip.Contains(':')) { $Ip } else { "${Ip}:5555" }

Write-Host "Connecting to $target. If the TV asks 'Allow USB debugging?', tick 'Always allow' and press OK."
$state = ''
for ($i = 0; $i -lt 30; $i++) {
    cmd /c "adb connect $target >nul 2>&1"
    $state = (cmd /c "adb -s $target get-state 2>nul" | Out-String).Trim()
    if ($state -eq 'device') { break }
    Start-Sleep -Seconds 2
}
if ($state -ne 'device') {
    Write-Error "Couldn't connect to $target. Check the IP, that the PC is on the same network, and that USB debugging is on."
    exit 1
}

# Strip Windows line endings: Android's shell can't run a script that has them.
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('tvtune-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    foreach ($file in 'tvtune.sh', 'packages.txt') {
        $text = [IO.File]::ReadAllText((Join-Path $PSScriptRoot $file)) -replace "`r", ''
        [IO.File]::WriteAllText((Join-Path $tmp $file), $text)  # UTF-8, no BOM
    }
    adb -s $target shell mkdir -p /data/local/tmp/tvtune
    adb -s $target push (Join-Path $tmp 'tvtune.sh') (Join-Path $tmp 'packages.txt') /data/local/tmp/tvtune/ | Out-Null
    adb -s $target shell sh /data/local/tmp/tvtune/tvtune.sh $Step | Tee-Object -FilePath (Join-Path $PSScriptRoot "tvtune-$Step.log")
}
finally {
    Remove-Item -Recurse -Force $tmp
}
