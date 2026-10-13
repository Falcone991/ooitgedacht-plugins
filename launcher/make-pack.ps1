# Maakt pack/ooitgedacht.mrpack van de Modrinth App-instance "Ooitgedacht".
# Gebruik:  powershell -ExecutionPolicy Bypass -File make-pack.ps1 [-Instance "Ooitgedacht"] [-Version "1.0.1"]
# Mods die op Modrinth staan worden als download-link opgenomen; andere mods gaan als bestand mee.
param(
    [string]$Instance = "Ooitgedacht",
    [string]$Version = (Get-Date -Format "yyyy.MM.dd-HHmm")
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$profileDir = Join-Path $env:APPDATA "ModrinthApp\profiles\$Instance"
if (-not (Test-Path $profileDir)) { throw "Instance niet gevonden: $profileDir" }

# Minecraft- en Fabric-versie uit het laatste log van de instance
$log = Get-Content (Join-Path $profileDir "logs\latest.log") -TotalCount 50 | Select-String "Loading Minecraft (\S+) with Fabric Loader (\S+)" | Select-Object -First 1
if (-not $log) { throw "Start de instance eerst een keer in de Modrinth App (versies staan in logs\latest.log)." }
$mcVersion = $log.Matches[0].Groups[1].Value
$loaderVersion = $log.Matches[0].Groups[2].Value
Write-Host "Minecraft $mcVersion, Fabric Loader $loaderVersion"

$folders = "mods", "resourcepacks", "shaderpacks"
$local = @()
foreach ($folder in $folders) {
    $dir = Join-Path $profileDir $folder
    if (Test-Path $dir) {
        Get-ChildItem $dir -File | Where-Object { $_.Extension -in ".jar", ".zip" } | ForEach-Object {
            $local += [pscustomobject]@{
                Path   = "$folder/$($_.Name)"
                File   = $_.FullName
                Size   = $_.Length
                Sha1   = (Get-FileHash $_.FullName -Algorithm SHA1).Hash.ToLower()
                Sha512 = (Get-FileHash $_.FullName -Algorithm SHA512).Hash.ToLower()
            }
        }
    }
}

$body = @{ hashes = @($local | ForEach-Object Sha1); algorithm = "sha1" } | ConvertTo-Json
$found = Invoke-RestMethod -Method Post -Uri "https://api.modrinth.com/v2/version_files" -Body $body `
    -ContentType "application/json" -Headers @{ "User-Agent" = "Falcone991/ooitgedacht-plugins (make-pack)" }

$files = @()
$extra = @()
foreach ($f in $local) {
    $v = $found.($f.Sha1)
    $url = $null
    if ($v) { $url = ($v.files | Where-Object { $_.hashes.sha1 -eq $f.Sha1 } | Select-Object -First 1).url }
    if ($url) {
        $files += [ordered]@{
            path      = $f.Path
            hashes    = [ordered]@{ sha1 = $f.Sha1; sha512 = $f.Sha512 }
            env       = [ordered]@{ client = "required"; server = "unsupported" }
            downloads = @($url)
            fileSize  = $f.Size
        }
        Write-Host "  [modrinth] $($f.Path)"
    } else {
        $extra += $f
        Write-Host "  [bestand]  $($f.Path)  (niet op Modrinth, gaat mee in het pack)"
    }
}

$index = [ordered]@{
    formatVersion = 1
    game          = "minecraft"
    versionId     = $Version
    name          = "OoitGedacht SMP"
    files         = $files
    dependencies  = [ordered]@{ minecraft = $mcVersion; "fabric-loader" = $loaderVersion }
}
$json = $index | ConvertTo-Json -Depth 10

$out = Join-Path $PSScriptRoot "pack\ooitgedacht.mrpack"
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
if (Test-Path $out) { Remove-Item $out }
$zip = [System.IO.Compression.ZipFile]::Open($out, "Create")
try {
    $entry = $zip.CreateEntry("modrinth.index.json")
    $w = New-Object System.IO.StreamWriter($entry.Open(), (New-Object System.Text.UTF8Encoding($false)))
    $w.Write($json); $w.Close()
    foreach ($f in $extra) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.File, "overrides/$($f.Path)") | Out-Null
    }
} finally { $zip.Dispose() }

Write-Host ""
Write-Host "Klaar: $out  (versie $Version, $($files.Count + $extra.Count) bestanden)"
