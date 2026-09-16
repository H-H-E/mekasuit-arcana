<#
.SYNOPSIS
    Headless runtime smoke test harness for the MekaSuit Arcana addon.

.DESCRIPTION
    Validates that a stock NeoForge dedicated-server instance on this machine can
    boot with the addon jar staged into its mods/ folder, then optionally boots it,
    waits for the server-ready gate, sends commands, and stops it cleanly.

    Non-destructive by design:
      * it never deletes, moves, or renames anything;
      * the only file it ever writes outside its own timestamped log directory is
        <Instance>\mods\<addon-jar-filename>;
      * it never edits server.properties, eula.txt, user_jvm_args.txt, or a world.

    Default mode is a DRY RUN. It preflights and prints exactly what it would do.
    Pass -Launch to actually start the server.

.PARAMETER Instance
    Dedicated-server instance root (the directory containing run.bat / mods /
    libraries / server.properties). Required; use a disposable test instance.

.PARAMETER AddonJar
    Path to the built addon jar to stage into <Instance>\mods. Optional: without
    it the script still performs preflight and can still boot the server, which is
    exactly what P1 (does the instance load) needs.

.PARAMETER JavaHome
    JDK 21 home. Defaults to JAVA_HOME; fails closed when absent.

.PARAMETER BootTimeoutSeconds
    How long to wait for the 'Done (...)! For help, type "help"' gate.

.PARAMETER StopGraceSeconds
    How long to wait for a clean 'stop' to take effect.

.PARAMETER HoldOpenSeconds
    Extra seconds to let the server tick after the boot gate, before stopping.

.PARAMETER Command
    Zero or more console commands to send after the boot gate is reached.

.PARAMETER PackIndex
    Directory holding the pack's *.pw.toml index. Defaults to the repo's mods/
    directory (three levels up from this script). Used to reconcile instance mod
    versions against the pack pins.

.PARAMETER Launch
    Actually start the server. Without this switch the script is a dry run.

.PARAMETER AllowVersionMismatch
    Downgrade the instance-vs-pack version reconciliation from a hard failure to
    a warning. Use deliberately, and report results with the mismatch attached.

.PARAMETER RequireIntegrationPass
    Opt-in post-boot gate. Runs the existing AUTO verification and the timing
    verification command, then requires every AUTO check to pass plus PASS
    arcana_timing_test.all. A failed integration gate exits 5 after clean stop.

.EXAMPLE
    .\runtime-smoke.ps1 -AddonJar ..\build\libs\mekasuit-arcana-0.1.0.jar

.EXAMPLE
    .\runtime-smoke.ps1 -AddonJar ..\build\libs\mekasuit-arcana-0.1.0.jar -Launch -HoldOpenSeconds 30

.NOTES
    EXIT CODES
      0  dry run passed, or boot gate reached and server stopped
      1  preflight failed
      2  boot timed out
      3  server exited or hit a fatal error before the boot gate
      4  addon jar missing, unreadable, or not the MekaSuit Arcana mod
      5  opt-in integration verification did not pass after a clean stop
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param(
    [Parameter(Mandatory = $true)]
    [string]   $Instance,
    [string]   $AddonJar,
    [string]   $JavaHome = $env:JAVA_HOME,
    [int]      $BootTimeoutSeconds = 900,
    [int]      $StopGraceSeconds = 60,
    [int]      $HoldOpenSeconds = 0,
    [string[]] $Command,
    [string]   $PackIndex,
    [switch]   $Launch,
    [switch]   $AllowVersionMismatch,
    [switch]   $RequireIntegrationPass
)

$ErrorActionPreference = 'Stop'

$bootGatePattern = 'Done \([0-9.,]+s\)! For help, type "help"'
$fatalPatterns = @(
    'Mod loading failures have occurred',
    'Exception in server tick loop',
    'Failed to start the server',
    'A fatal error has been detected by the Java Runtime Environment',
    'has failed to load correctly'
)
$requiredModFamilies = @(
    @{ Label = 'Mekanism';            Prefix = 'Mekanism-' },
    @{ Label = "Iron's Spellbooks";   Prefix = 'irons_spellbooks-' },
    @{ Label = 'irons_lib (ISB dep)'; Prefix = 'irons_lib-' }
)

$problems = New-Object 'System.Collections.Generic.List[string]'
$notes    = New-Object 'System.Collections.Generic.List[string]'

function Add-Problem { param([string] $Message) [void]$problems.Add($Message) }
function Add-Note    { param([string] $Message) [void]$notes.Add($Message) }

# Computed with .NET rather than Get-FileHash on purpose: Get-FileHash honours
# -WhatIf and would print an empty digest during a dry run.
function Get-FileSha256Hex {
    param([string] $Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        try { return (($sha.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '') }
        finally { $stream.Dispose() }
    } finally { $sha.Dispose() }
}

function Test-TcpPortOpen {
    param([int] $Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if ($connect.AsyncWaitHandle.WaitOne(400)) {
            $client.EndConnect($connect)
            return $true
        }
        return $false
    } catch {
        return $false
    } finally {
        try { $client.Close() } catch { }
    }
}

# Reads only the text appended since $Offset, then advances $Offset. Tolerates the
# log being truncated/rotated by resetting to the start.
function Read-AppendedText {
    param([string] $Path, [ref] $Offset)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    $stream = $null
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        if ($stream.Length -lt $Offset.Value) { $Offset.Value = 0L }
        [void]$stream.Seek($Offset.Value, [System.IO.SeekOrigin]::Begin)
        $reader = New-Object System.IO.StreamReader($stream)
        $text = $reader.ReadToEnd()
        $Offset.Value = $stream.Position
        return $text
    } catch {
        return ''
    } finally {
        if ($stream) { $stream.Dispose() }
    }
}

function Send-ConsoleLine {
    param([System.Diagnostics.Process] $Target, [string] $Line)
    # Raw byte write on purpose: this avoids the UTF-8 BOM that Windows
    # PowerShell's redirected stdin writer emits on its first access.
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($Line + [string][char]10)
    $Target.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $Target.StandardInput.BaseStream.Flush()
}

Write-Host ''
Write-Host 'MekaSuit Arcana - runtime smoke test'
Write-Host ('  mode     : ' + $(if ($Launch) { 'LAUNCH' } else { 'DRY RUN (pass -Launch to start the server)' }))
Write-Host ('  instance : ' + $Instance)
Write-Host ''

# ---------------------------------------------------------------- preflight ----

$instanceOk = Test-Path -LiteralPath $Instance -PathType Container
if (-not $instanceOk) {
    Add-Problem ('Instance directory not found: ' + $Instance)
}

$serverProperties = Join-Path $Instance 'server.properties'
$eulaFile         = Join-Path $Instance 'eula.txt'
$jvmArgsFile      = Join-Path $Instance 'user_jvm_args.txt'
$modsDir          = Join-Path $Instance 'mods'

if ($instanceOk) {
    if (-not (Test-Path -LiteralPath $serverProperties -PathType Leaf)) {
        Add-Problem ('Missing server.properties in ' + $Instance + ' - this is not a dedicated-server instance')
    }
    if (-not (Test-Path -LiteralPath $eulaFile -PathType Leaf)) {
        Add-Problem ('Missing eula.txt in ' + $Instance + ' - start the server once and accept the Minecraft EULA by hand; this script will not write it for you')
    } else {
        $eulaText = Get-Content -LiteralPath $eulaFile -Raw
        if ($eulaText -notmatch 'eula\s*=\s*true') {
            Add-Problem ('eula.txt does not contain eula=true in ' + $Instance + ' - accepting the EULA is a human decision this script will not make')
        }
    }
    if (-not (Test-Path -LiteralPath $jvmArgsFile -PathType Leaf)) {
        Add-Problem ('Missing user_jvm_args.txt in ' + $Instance + ' - the launch line references it, so the instance would not start')
    }
    if (-not (Test-Path -LiteralPath $modsDir -PathType Container)) {
        Add-Problem ('Missing mods directory in ' + $Instance)
    }
}

# --- java -------------------------------------------------------------------
$javaExe = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
    Add-Problem ('JDK 21 not found at ' + $javaExe + ' - install it or pass -JavaHome')
} else {
    Add-Note ('java          : ' + $javaExe)
}

# --- neoforge launch args ---------------------------------------------------
$winArgsRel = $null
$neoRoot = Join-Path $Instance 'libraries\net\neoforged\neoforge'
if ($instanceOk) {
    if (-not (Test-Path -LiteralPath $neoRoot -PathType Container)) {
        Add-Problem ('Missing NeoForge libraries at ' + $neoRoot + ' - this instance has no server launcher installed')
    } else {
        $candidates = @(Get-ChildItem -LiteralPath $neoRoot -Recurse -Filter 'win_args.txt' -File -ErrorAction SilentlyContinue)
        if ($candidates.Count -eq 0) {
            Add-Problem ('No win_args.txt under ' + $neoRoot)
        } else {
            $preferred = $null
            $runBat = Join-Path $Instance 'run.bat'
            if (Test-Path -LiteralPath $runBat -PathType Leaf) {
                $match = [regex]::Match((Get-Content -LiteralPath $runBat -Raw), 'neoforge/([0-9][0-9.]*)/win_args\.txt')
                if ($match.Success) { $preferred = $match.Groups[1].Value }
            }
            $picked = $null
            if ($preferred) {
                $picked = $candidates | Where-Object { $_.Directory.Name -eq $preferred } | Select-Object -First 1
            }
            if (-not $picked) {
                $picked = $candidates | Sort-Object -Property { [version]$_.Directory.Name } -Descending | Select-Object -First 1
            }
            $winArgsRel = $picked.FullName.Substring($Instance.Length).TrimStart([char]92)
            Add-Note ('neoforge args : ' + $winArgsRel + '  (NeoForge ' + $picked.Directory.Name + ')')
            if ($preferred -and $picked.Directory.Name -ne $preferred) {
                Add-Note ('note          : run.bat names NeoForge ' + $preferred + ' but ' + $picked.Directory.Name + ' was selected')
            }
        }
    }
}

# --- required mods ----------------------------------------------------------
$modJars = @()
if (Test-Path -LiteralPath $modsDir -PathType Container) {
    $modJars = @(Get-ChildItem -LiteralPath $modsDir -Filter '*.jar' -File -ErrorAction SilentlyContinue)
}
$modsByFamily = @{}
foreach ($family in $requiredModFamilies) {
    $found = @($modJars | Where-Object { $_.Name -like ($family.Prefix + '*.jar') })
    $modsByFamily[$family.Prefix] = $found
    if ($found.Count -eq 0) {
        Add-Problem ('Instance mods/ has no ' + $family.Prefix + '*.jar. The addon declares a hard dependency, so the loader would fail at startup with "requires ... Currently, ... is not installed"')
    } else {
        Add-Note ($family.Label.PadRight(13) + ' : ' + (($found | ForEach-Object { $_.Name }) -join ', '))
    }
}
Add-Note ('total mod jars: ' + $modJars.Count)

# --- reconcile instance versions against the pack pins ----------------------
if (-not $PackIndex) {
    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
    $candidateIndex = Join-Path $repoRoot 'mods'
    if (Test-Path -LiteralPath $candidateIndex -PathType Container) { $PackIndex = $candidateIndex }
}
$pins = @{}
if ($PackIndex -and (Test-Path -LiteralPath $PackIndex -PathType Container)) {
    foreach ($pinFile in Get-ChildItem -LiteralPath $PackIndex -Filter '*.pw.toml' -File -ErrorAction SilentlyContinue) {
        $match = [regex]::Match((Get-Content -LiteralPath $pinFile.FullName -Raw), '(?m)^\s*filename\s*=\s*"([^"]+)"')
        if ($match.Success) { $pins[$pinFile.BaseName] = $match.Groups[1].Value }
    }
    Add-Note ('pack index   : ' + $PackIndex + ' (' + $pins.Count + ' pinned filenames)')

    foreach ($family in $requiredModFamilies) {
        $wanted = @($pins.Values | Where-Object { $_ -like ($family.Prefix + '*') })
        $have = @($modsByFamily[$family.Prefix] | ForEach-Object { $_.Name })
        if ($wanted.Count -eq 0) {
            Add-Note ($family.Label + ': no pin found in the pack index; instance has [' + ($have -join ', ') + ']')
            continue
        }
        $matched = $false
        foreach ($want in $wanted) { if ($have -contains $want) { $matched = $true } }
        if ($matched) {
            Add-Note ($family.Label + ': matches pack pin [' + ($wanted -join ', ') + ']')
        } else {
            $mismatch = ($family.Label + ': instance has [' + ($have -join ', ') + '] but the pack pins [' + ($wanted -join ', ') + ']')
            if ($AllowVersionMismatch) { Add-Note ('MISMATCH (allowed) ' + $mismatch) } else { Add-Problem $mismatch }
        }
    }
} else {
    Add-Note 'pack index   : not found - instance-vs-pack version reconciliation was skipped'
}

# --- port availability -----------------------------------------------------
if (Test-Path -LiteralPath $serverProperties -PathType Leaf) {
    $portMatch = [regex]::Match((Get-Content -LiteralPath $serverProperties -Raw), '(?m)^server-port=(\d+)')
    if ($portMatch.Success) {
        $serverPort = [int]$portMatch.Groups[1].Value
        if (Test-TcpPortOpen -Port $serverPort) {
            Add-Problem ('Port ' + $serverPort + ' is already accepting connections on 127.0.0.1 - a server is probably already running, so this launch would fail')
        } else {
            Add-Note ('server port  : ' + $serverPort + ' (free)')
        }
    }
}

# --- optional addon jar ----------------------------------------------------
$stageSource = $null
$stageTarget = $null
$addonJarProblem = $false
if ($AddonJar) {
    if (-not (Test-Path -LiteralPath $AddonJar -PathType Leaf)) {
        Add-Problem ('Addon jar not found: ' + $AddonJar)
        $addonJarProblem = $true
    } else {
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
            $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $AddonJar).Path)
            try {
                $entry = @($zip.Entries | Where-Object { $_.FullName -eq 'META-INF/neoforge.mods.toml' })
                if ($entry.Count -eq 0) {
                    Add-Problem ('Addon jar has no META-INF/neoforge.mods.toml: ' + $AddonJar)
                    $addonJarProblem = $true
                } else {
                    $entryReader = New-Object System.IO.StreamReader($entry[0].Open())
                    $toml = $entryReader.ReadToEnd()
                    $entryReader.Dispose()
                    if ($toml -notmatch 'modId\s*=\s*"mekasuitarcana"') {
                        Add-Problem ('Addon jar does not declare modId = "mekasuitarcana": ' + $AddonJar)
                        $addonJarProblem = $true
                    } else {
                        $hash = Get-FileSha256Hex -Path $AddonJar
                        $size = (Get-Item -LiteralPath $AddonJar).Length
                        Add-Note ('addon jar    : ' + $AddonJar)
                        Add-Note ('addon sha256 : ' + $hash)
                        Add-Note ('addon bytes  : ' + $size)
                        $stageSource = (Resolve-Path -LiteralPath $AddonJar).Path
                        $stageTarget = Join-Path $modsDir (Split-Path -Path $AddonJar -Leaf)
                    }
                }
            } finally {
                $zip.Dispose()
            }
        } catch {
            Add-Problem ('Could not read addon jar ' + $AddonJar + ' : ' + $_.Exception.Message)
            $addonJarProblem = $true
        }
    }
    if ($stageTarget) {
        $duplicates = @($modJars | Where-Object { $_.Name -like '*mekasuit*' -and $_.FullName -ne $stageTarget })
        if ($duplicates.Count -gt 0) {
            Add-Problem ('Another MekaSuit Arcana jar is already staged: ' + (($duplicates | ForEach-Object { $_.Name }) -join ', ') + ' - two copies of one mod id fail to load. Remove one by hand; this script will not delete it')
        }
    }
} else {
    Add-Note 'addon jar    : not supplied - preflight/boot will run without staging it'
}

# ---------------------------------------------------------------- report ------

Write-Host 'Preflight'
foreach ($note in $notes) { Write-Host ('  OK   ' + $note) }
foreach ($problem in $problems) { Write-Host ('  FAIL ' + $problem) }
Write-Host ''

if ($problems.Count -gt 0) {
    Write-Host ('Preflight FAILED with ' + $problems.Count + ' blocking problem(s). Nothing was launched and nothing was written.')
    if ($addonJarProblem) { exit 4 }
    exit 1
}

$plan = @()
$plan += 'launch plan'
$plan += '  working dir : ' + $Instance
$plan += '  java        : ' + $javaExe
$plan += '  arguments   : @user_jvm_args.txt @' + $winArgsRel + ' nogui'
$plan += '  boot timeout: ' + $BootTimeoutSeconds + 's, stop grace ' + $StopGraceSeconds + 's, hold open ' + $HoldOpenSeconds + 's'
if ($stageSource) {
    $plan += '  stage       : ' + $stageSource + '  ->  ' + $stageTarget
} else {
    $plan += '  stage       : (skipped - no -AddonJar)'
}
if ($Command) {
    foreach ($line in $Command) { $plan += '  command     : ' + $line }
}
if ($RequireIntegrationPass) {
    $plan += '  integration : requires AUTO all-checks and PASS arcana_timing_test.all'
}
foreach ($line in $plan) { Write-Host $line }
Write-Host ''

if (-not $Launch) {
    Write-Host 'DRY RUN complete. Nothing was launched and nothing was written.'
    Write-Host 'Re-run with -Launch to execute the plan above.'
    exit 0
}

$stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path (Join-Path $Instance 'logs') ('runtime-smoke-' + $stamp)

# ---------------------------------------------------------------- execute -----

if ($stageSource) {
    if ($PSCmdlet.ShouldProcess($stageTarget, 'Stage addon jar into instance mods')) {
        $sameContent = $false
        if (Test-Path -LiteralPath $stageTarget -PathType Leaf) {
            $existing = Get-FileSha256Hex -Path $stageTarget
            if ($existing -eq (Get-FileSha256Hex -Path $stageSource)) { $sameContent = $true }
        }
        if ($sameContent) {
            Write-Host ('Already staged and identical, leaving as is: ' + $stageTarget)
        } else {
            Copy-Item -LiteralPath $stageSource -Destination $stageTarget -Force
            Write-Host ('Staged: ' + $stageTarget)
        }
    }
}

$enc = New-Object System.Text.UTF8Encoding($false)
$commandsToSend = @($Command | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($RequireIntegrationPass) {
    if ($commandsToSend -notcontains 'mekasuitarcana_runtime_test auto') {
        $commandsToSend += 'mekasuitarcana_runtime_test auto'
    }
    if ($commandsToSend -notcontains 'arcana_timing_test') {
        $commandsToSend += 'arcana_timing_test'
    }
}
if ($PSCmdlet.ShouldProcess($Instance, 'Start the NeoForge dedicated server')) {
    [void][System.IO.Directory]::CreateDirectory($logDir)

    $stdoutPath = Join-Path $logDir 'stdout.log'
    $stderrPath = Join-Path $logDir 'stderr.log'
    $summaryPath = Join-Path $logDir 'summary.txt'
    $latestLog = Join-Path $Instance 'logs\latest.log'
    $offset = 0L
    if (Test-Path -LiteralPath $latestLog -PathType Leaf) { $offset = (Get-Item -LiteralPath $latestLog).Length }

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $javaExe
    $startInfo.Arguments = '@user_jvm_args.txt @' + $winArgsRel + ' nogui'
    $startInfo.WorkingDirectory = $Instance
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    # Set the encoding before Process.Start so the first command has no BOM.
    $startInfo.StandardInputEncoding = $enc

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    Write-Host ('Starting server (pid will be reported below)...')
    $startedAt = Get-Date
    if (-not $process.Start()) { throw 'Failed to start the java process' }
    Write-Host ('  pid: ' + $process.Id)

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    $deadline = $startedAt.AddSeconds($BootTimeoutSeconds)
    $booted = $false
    $fatalSeen = $null
    $peakBytes = 0L
    $transcript = New-Object System.Text.StringBuilder

    while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline.ToUniversalTime()) {
        $process.Refresh()
        if ($process.WorkingSet64 -gt $peakBytes) { $peakBytes = $process.WorkingSet64 }
        $chunk = Read-AppendedText -Path $latestLog -Offset ([ref]$offset)
        if ($chunk) {
            [void]$transcript.Append($chunk)
            if (-not $fatalSeen) {
                foreach ($pattern in $fatalPatterns) {
                    if ($chunk -match [regex]::Escape($pattern)) { $fatalSeen = $pattern; break }
                }
                if (-not $fatalSeen -and ($transcript.ToString() -match $bootGatePattern)) { $booted = $true }
            }
        }
        if ($booted -or $fatalSeen) { break }
        Start-Sleep -Milliseconds 500
    }

    # If the gate appeared in the same instant we exited the loop, honour it.
    if (-not $booted -and -not $fatalSeen -and ($transcript.ToString() -match $bootGatePattern)) { $booted = $true }

    $result = 'UNKNOWN'
    $exitCode = 0
    $cleanStopped = $false
    $integrationPass = $false

    if ($fatalSeen) {
        $result = 'FATAL'
        $exitCode = 3
        Write-Host ('FATAL marker seen in log: ' + $fatalSeen)
    } elseif ($booted) {
        $result = 'BOOTED'
        Write-Host 'Boot gate reached: server is up.'
        if ($commandsToSend.Count -gt 0) {
            foreach ($line in $commandsToSend) {
                Write-Host ('  > ' + $line)
                Send-ConsoleLine -Target $process -Line $line
                Start-Sleep -Milliseconds 750
            }
        }
        if ($HoldOpenSeconds -gt 0) {
            Write-Host ('Holding open for ' + $HoldOpenSeconds + 's...')
            Start-Sleep -Seconds $HoldOpenSeconds
        }
        Write-Host 'Sending stop and waiting for a clean shutdown...'
        Send-ConsoleLine -Target $process -Line 'stop'
        $stopDeadline = (Get-Date).AddSeconds($StopGraceSeconds)
        while (-not $process.HasExited -and (Get-Date) -lt $stopDeadline) { Start-Sleep -Milliseconds 500 }
        $cleanStopped = $process.HasExited
        if (-not $process.HasExited) {
            Write-Host ('Server pid ' + $process.Id + ' is still running after ' + $StopGraceSeconds + 's. Leaving it alone - stop it by hand (Stop-Process -Id ' + $process.Id + ') after checking logs\latest.log')
        }
    } elseif ($process.HasExited) {
        $result = 'EXITED_EARLY'
        $exitCode = 3
        Write-Host ('Server exited before the boot gate (exit code ' + $process.ExitCode + ').')
    } else {
        $result = 'TIMEOUT'
        $exitCode = 2
        Write-Host ('Boot gate not reached within ' + $BootTimeoutSeconds + 's. Attempting a clean stop...')
        Send-ConsoleLine -Target $process -Line 'stop'
        $stopDeadline = (Get-Date).AddSeconds($StopGraceSeconds)
        while (-not $process.HasExited -and (Get-Date) -lt $stopDeadline) { Start-Sleep -Milliseconds 500 }
        $cleanStopped = $process.HasExited
        if (-not $process.HasExited) {
            Write-Host ('Server pid ' + $process.Id + ' is still running. Leaving it alone; stop it by hand with Stop-Process -Id ' + $process.Id)
        }
    }

    # The process may still be alive in the timeout/unclean paths; only read the
    # async buffers once it has exited, otherwise ReadToEndAsync would block.
    $stdoutText = ''
    $stderrText = ''
    if ($process.HasExited) {
        $tail = Read-AppendedText -Path $latestLog -Offset ([ref]$offset)
        if ($tail) { [void]$transcript.Append($tail) }
        try { $stdoutText = $stdoutTask.Result } catch { $stdoutText = '' }
        try { $stderrText = $stderrTask.Result } catch { $stderrText = '' }
    }

    if ($RequireIntegrationPass -and $cleanStopped -and $booted) {
        $lines = @($transcript.ToString() -split "`r?`n")
        $autoLines = @($lines | Where-Object { $_ -match '(?i)\bAUTO\b' })
        $autoPassed = $false
        foreach ($autoLine in $autoLines) {
            $autoPassed = $autoLine -match '(?i)install=Check\[passed=True\b' `
                -and $autoLine -match '(?i)support=Check\[passed=True\b' `
                -and $autoLine -match '(?i)powered=Check\[passed=True\b' `
                -and $autoLine -match '(?i)castPayment=True' `
                -and $autoLine -match '(?i)caps=CapCheck\[passed=True\b' `
                -and $autoLine -match '(?i)serverCaps=Check\[passed=True\b' `
                -and $autoLine -match '(?i)noDrySuitBuff=Check\[passed=True\b' `
                -and $autoLine -notmatch '(?i)\bpassed=False\b'
            if ($autoPassed) { break }
        }
        $timingPassed = [bool]($lines | Where-Object { $_ -match '(?i)\bPASS\s+arcana_timing_test\.all\b' })
        $arcanaFailed = [bool]($lines | Where-Object { $_ -match '(?i)\bFAIL\s+arcana_' })
        $commandError = [bool]($lines | Where-Object {
            $_ -match '(?i)(Unknown command|Unknown or incomplete command|Could not execute command|An unexpected error occurred.*command|Command failed)'
        })
        $integrationPass = $autoPassed -and $timingPassed -and (-not $arcanaFailed) -and (-not $commandError)
        $integrationLabel = if ($integrationPass) { 'PASS' } else { 'FAIL' }
        $integrationMessage = 'Integration gate: {0} (AUTO={1}, timing={2}, arcanaFail={3}, commandError={4})' -f $integrationLabel, $autoPassed, $timingPassed, $arcanaFailed, $commandError
        Write-Host $integrationMessage
        if (-not $integrationPass) { $exitCode = 5 }
    }
    [System.IO.File]::WriteAllText($stdoutPath, $stdoutText, $enc)
    [System.IO.File]::WriteAllText($stderrPath, $stderrText, $enc)

    $elapsed = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
    $peakMiB = [math]::Round($peakBytes / 1MB, 1)

    $summary = @()
    $summary += 'MekaSuit Arcana runtime smoke test'
    $summary += 'result        : ' + $result
    $summary += 'exit code     : ' + $exitCode
    $summary += 'instance      : ' + $Instance
    $summary += 'java          : ' + $javaExe
    $summary += 'arguments     : @user_jvm_args.txt @' + $winArgsRel + ' nogui'
    $summary += 'addon jar     : ' + $(if ($stageTarget) { $stageTarget } else { '(none staged)' })
    $summary += 'elapsed s     : ' + $elapsed
    $summary += 'peak working set MiB: ' + $peakMiB
    $summary += 'boot gate     : ' + $bootGatePattern
    $summary += 'fatal marker  : ' + $(if ($fatalSeen) { $fatalSeen } else { '(none)' })
    $summary += 'integration   : ' + $(if (-not $RequireIntegrationPass) { 'not required' } elseif ($integrationPass) { 'PASS' } else { 'FAIL' })
    $summary += 'mod jars      : ' + $modJars.Count
    $summary += 'log dir       : ' + $logDir
    $summary += '--- preflight report ---'
    $summary += $notes.ToArray()
    if ($problems.Count -gt 0) { $summary += 'BLOCKING PROBLEMS:'; $summary += $problems.ToArray() } else { $summary += 'blocking problems: (none)' }
    $summary += 'note          : boot alone proves loading only; integration PASS additionally proves the named synthetic server assertions, not a connected client or full-pack playtest.'
    [System.IO.File]::WriteAllLines($summaryPath, $summary, $enc)

    Write-Host ''
    Write-Host ('Result: ' + $result)
    Write-Host ('Logs:   ' + $logDir)
    foreach ($line in $summary) { Write-Host ('  ' + $line) }
    exit $exitCode
}

Write-Host 'Nothing to do.'
exit 0
