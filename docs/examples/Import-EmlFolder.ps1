<#
.SYNOPSIS
    Watches a folder and imports every new .eml file into an Overmail inbox.

.DESCRIPTION
    Every -IntervalSeconds the folder is looked at, and every .eml file that arrived since the last
    pass is sent to POST /api/users/me/inboxes/{inboxId}/import, oldest first. The server stores the
    mail, builds its preview and has the assistant classify it, like a mail it read off IMAP.

    How far the import got is kept in a state file in the watched folder, named after the instance
    and the inbox: the same folder can feed several inboxes or instances, each with its own state,
    and a restart carries on where the last run stopped.

    "Arrived" is the later of a file's creation and last write time. Copying a file into the folder
    sets its creation time to now, so an old mail copied in later is still picked up. A file moved
    in from the same drive keeps both times, so one older than what was already imported is not:
    copy files in rather than moving them. A file whose time lies in the future -- a clock that was
    off -- holds back every file after it until that time has come.

    A file the server refuses as no mail it can store (400) is logged and skipped -- sending it
    again would change nothing. A mail the inbox already holds (409) counts as imported. Anything
    else -- no connection, a wrong key, the server failing -- stops the pass at that file, and the
    next pass starts there again, so the order is kept and nothing is left behind.

    Runs on Windows PowerShell 5.1 and PowerShell 7.

.PARAMETER Instance
    The Overmail instance, e.g. https://mail.example.com

.PARAMETER ApiKey
    An api key (Settings > API). Defaults to the OVERMAIL_API_KEY environment variable, which keeps
    the key out of the command line and the shell history.

.PARAMETER InboxId
    The id of the inbox the mails go into.

.PARAMETER Path
    The folder to watch. Only the folder itself, not its subfolders.

.PARAMETER IntervalSeconds
    How often the folder is looked at. 30 seconds by default.

.PARAMETER Once
    Import what is there and stop, instead of watching.

.EXAMPLE
    $env:OVERMAIL_API_KEY = "om_..."
    .\Import-EmlFolder.ps1 -Instance https://mail.example.com -InboxId 0b6f... -Path C:\Mail\Export
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Instance,
    [string] $ApiKey = $env:OVERMAIL_API_KEY,
    [Parameter(Mandatory)] [guid] $InboxId,
    [Parameter(Mandatory)] [string] $Path,
    [ValidateRange(1, 86400)] [int] $IntervalSeconds = 30,
    [switch] $Once
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "No api key: pass -ApiKey or set OVERMAIL_API_KEY."
}
if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
    throw "No such folder: $Path"
}

$Path = (Resolve-Path -LiteralPath $Path).ProviderPath
$Instance = $Instance.TrimEnd("/")
$importUrl = "$Instance/api/users/me/inboxes/$InboxId/import?type=eml"

# One state file per instance and inbox. The host goes into the name as it is, minus what a file
# name cannot hold, so a look into the folder says which file belongs to what.
$instanceName = ([uri] $Instance).Authority -replace '[^A-Za-z0-9.-]', '_'
$statePath = Join-Path $Path ".overmail-import_${instanceName}_$InboxId.json"

Add-Type -AssemblyName System.Net.Http
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromMinutes(5)
$http.DefaultRequestHeaders.Authorization =
    [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $ApiKey)

function Write-Log([string] $Message) {
    Write-Host "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $Message"
}

<#
    Where the import stands: the arrival time of the last file dealt with, and its name. The name
    is what keeps files that arrived in the same instant apart, which a copy of many files makes
    common.
#>
function Read-State {
    if (-not (Test-Path -LiteralPath $statePath)) {
        return [pscustomobject] @{ Until = [datetime]::MinValue; LastFile = "" }
    }
    $json = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    return [pscustomobject] @{
        # Ticks rather than a date string: ConvertFrom-Json turns a date string into a local time
        # in one PowerShell version and leaves it a string in the other.
        Until    = [datetime]::new([long] $json.imported_until_ticks, [DateTimeKind]::Utc)
        LastFile = [string] $json.last_file
    }
}

function Write-State([datetime] $Until, [string] $LastFile) {
    $json = [ordered] @{
        instance             = $Instance
        inbox_id             = "$InboxId"
        imported_until       = $Until.ToString("o")
        imported_until_ticks = $Until.Ticks
        last_file            = $LastFile
    } | ConvertTo-Json

    # Written next to it and moved over it, so a crash halfway leaves the old state, not half a file.
    $temporary = "$statePath.tmp"
    [System.IO.File]::WriteAllText($temporary, $json)
    if (Test-Path -LiteralPath $statePath) {
        [System.IO.File]::Replace($temporary, $statePath, [NullString]::Value)
    } else {
        [System.IO.File]::Move($temporary, $statePath)
    }
}

function Get-Arrival([System.IO.FileInfo] $File) {
    if ($File.CreationTimeUtc -gt $File.LastWriteTimeUtc) { $File.CreationTimeUtc } else { $File.LastWriteTimeUtc }
}

<#
    The files that arrived after [State], oldest first, and by name among those that arrived at
    once. Both the filter and the sort compare names ordinally: Sort-Object would compare them by
    culture and ignoring case, and a file sorted after the last one by that but before it by the
    filter would never be imported.
#>
function Get-NewFiles($State) {
    $files = @(
        Get-ChildItem -LiteralPath $Path -Filter *.eml -File |
            # The filter alone also takes `.emlx` on Windows, which matches extensions of three
            # characters against longer ones.
            Where-Object { $_.Extension -eq ".eml" } |
            ForEach-Object { [pscustomobject] @{ File = $_; Arrival = Get-Arrival $_ } } |
            Where-Object {
                $_.Arrival -gt $State.Until -or
                    ($_.Arrival -eq $State.Until -and [string]::CompareOrdinal($_.File.Name, $State.LastFile) -gt 0)
            }
    )
    [Array]::Sort($files, [Comparison[object]] {
        param($a, $b)
        $byArrival = $a.Arrival.CompareTo($b.Arrival)
        if ($byArrival -ne 0) { return $byArrival }
        return [string]::CompareOrdinal($a.File.Name, $b.File.Name)
    })
    return , $files
}

<#
    Sends one file. True when it is dealt with -- imported, already there, or never importable --
    and false when it has to be tried again.
#>
function Send-File([System.IO.FileInfo] $File) {
    try {
        # Opened without sharing: a file that is still being written fails here and waits for the
        # next pass, rather than going out half.
        $stream = [System.IO.File]::Open($File.FullName, "Open", "Read", "None")
        try {
            $bytes = [byte[]]::new($stream.Length)
            $read = 0
            while ($read -lt $bytes.Length) {
                $count = $stream.Read($bytes, $read, $bytes.Length - $read)
                if ($count -le 0) { break }
                $read += $count
            }
        } finally {
            $stream.Dispose()
        }
    } catch {
        Write-Log "WAIT     $($File.Name): cannot be read yet ($($_.Exception.Message))"
        return $false
    }

    $content = [System.Net.Http.ByteArrayContent]::new($bytes)
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("message/rfc822")
    try {
        $response = $http.PostAsync($importUrl, $content).GetAwaiter().GetResult()
    } catch {
        Write-Log "ERROR    $($File.Name): $($_.Exception.GetBaseException().Message)"
        return $false
    } finally {
        $content.Dispose()
    }

    try {
        $status = [int] $response.StatusCode
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } finally {
        $response.Dispose()
    }

    switch ($status) {
        201 { Write-Log "IMPORTED $($File.Name)"; return $true }
        409 { Write-Log "KNOWN    $($File.Name): the inbox holds this mail already"; return $true }
        400 { Write-Log "SKIPPED  $($File.Name): $(Get-ErrorMessage $body)"; return $true }
        default {
            Write-Log "ERROR    $($File.Name): $status $(Get-ErrorMessage $body)"
            return $false
        }
    }
}

<# The message of the api's error payload, or the body as it is when it is not one. #>
function Get-ErrorMessage([string] $Body) {
    try {
        $message = ($Body | ConvertFrom-Json).error.message
        if ($message) { return $message }
    } catch {
    }
    return $Body
}

function Invoke-Pass {
    $state = Read-State
    foreach ($entry in (Get-NewFiles $state)) {
        if (-not (Send-File $entry.File)) {
            # Stopped here so the order holds: the next pass starts with this file again.
            return
        }
        Write-State -Until $entry.Arrival -LastFile $entry.File.Name
    }
}

Write-Log "Importing .eml files from $Path into inbox $InboxId on $Instance"
Write-Log "State: $statePath"

try {
    while ($true) {
        Invoke-Pass
        if ($Once) { break }
        Start-Sleep -Seconds $IntervalSeconds
    }
} finally {
    $http.Dispose()
}
