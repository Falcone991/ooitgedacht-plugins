# Bouwt target\OoitGedacht.exe met de C#-compiler die in Windows zit (.NET Framework 4.8).
# Gebruik:  powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = $PSScriptRoot
$target = Join-Path $root "target"
New-Item -ItemType Directory -Force $target | Out-Null

# icon.ico maken van assets\icon.png (pixel-art: groot = scherp opschalen, klein = netjes verkleinen)
$src = [System.Drawing.Image]::FromFile((Join-Path $root "assets\icon.png"))
$pngs = foreach ($size in 256, 64, 48, 32, 16) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    if ($size -ge $src.Width) {
        $g.InterpolationMode = "NearestNeighbor"; $g.PixelOffsetMode = "Half"
    } else {
        $g.InterpolationMode = "HighQualityBicubic"
    }
    $g.DrawImage($src, 0, 0, $size, $size)
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    , @($size, $ms.ToArray())
}
$src.Dispose()
$ico = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $ico
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$pngs.Count)
$offset = 6 + 16 * $pngs.Count
foreach ($p in $pngs) {
    $s = $p[0] % 256
    $w.Write([byte]$s); $w.Write([byte]$s); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$p[1].Length); $w.Write([uint32]$offset)
    $offset += $p[1].Length
}
foreach ($p in $pngs) { $w.Write($p[1]) }
$w.Flush()
$icoPath = Join-Path $target "icon.ico"
[System.IO.File]::WriteAllBytes($icoPath, $ico.ToArray())

$fw = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319"
$csc = Join-Path $fw "csc.exe"
$refs = "System.IO.Compression.dll", "System.Web.Extensions.dll", "Microsoft.VisualBasic.dll", "System.Windows.Forms.dll", "System.Drawing.dll"
$cscArgs = @("/nologo", "/target:winexe", "/optimize+", "/out:$(Join-Path $target 'OoitGedacht.exe')",
    "/win32icon:$icoPath", "/resource:$(Join-Path $root 'assets\icon.png'),OoitGedacht.icon.png") +
    ($refs | ForEach-Object { "/r:$(Join-Path $fw $_)" }) +
    @((Join-Path $root "src\OoitGedacht.cs"))
& $csc $cscArgs
if ($LASTEXITCODE -ne 0) { throw "Bouwen mislukt" }
Write-Host "Klaar: $(Join-Path $target 'OoitGedacht.exe')"
