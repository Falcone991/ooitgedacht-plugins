# Tekent het uitlegplaatje voor /launcher (Windows-waarschuwing: "Meer info" -> "Toch uitvoeren").
# Gebruik:  powershell -ExecutionPolicy Bypass -File launcher\make-help-image.ps1 -Out ooitgedacht-bot\src\main\resources\launcher-hulp.png
param([string]$Out)
Add-Type -AssemblyName System.Drawing
$W = 1280; $H = 560
$bmp = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = "AntiAlias"; $g.TextRenderingHint = "ClearTypeGridFit"
$g.Clear([System.Drawing.Color]::FromArgb(43, 45, 49))

$blue = [System.Drawing.Color]::FromArgb(0, 103, 192)
$white = [System.Drawing.Brushes]::White
$light = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(225, 235, 248))
$yellow = [System.Drawing.Color]::FromArgb(255, 204, 0)
$fTitle = New-Object System.Drawing.Font("Segoe UI", 19)
$fText = New-Object System.Drawing.Font("Segoe UI", 11)
$fLink = New-Object System.Drawing.Font("Segoe UI", 11, [System.Drawing.FontStyle]::Underline)
$fBtn = New-Object System.Drawing.Font("Segoe UI Semibold", 11)
$fStep = New-Object System.Drawing.Font("Segoe UI", 20, [System.Drawing.FontStyle]::Bold)
$fBadge = New-Object System.Drawing.Font("Segoe UI", 16, [System.Drawing.FontStyle]::Bold)
$center = New-Object System.Drawing.StringFormat; $center.Alignment = "Center"; $center.LineAlignment = "Center"

function Button($x, $y, $text) {
    $g.FillRectangle($white, $x, $y, 150, 36)
    $g.DrawString($text, $fBtn, [System.Drawing.Brushes]::Black, (New-Object System.Drawing.RectangleF($x, $y, 150, 36)), $center)
}
function Highlight($x, $y, $w, $h, $n, $side) {
    $pen = New-Object System.Drawing.Pen($yellow, 5)
    $g.DrawRectangle($pen, $x - 8, $y - 8, $w + 16, $h + 16)
    if ($side -eq "right") { $bx = $x + $w + 18; $by = $y + $h / 2 - 20 } else { $bx = $x + $w / 2 - 20; $by = $y - 62 }
    $g.FillEllipse((New-Object System.Drawing.SolidBrush $yellow), $bx, $by, 40, 40)
    $g.DrawString("$n", $fBadge, [System.Drawing.Brushes]::Black, (New-Object System.Drawing.RectangleF($bx, $by, 40, 40)), $center)
}
function Dialog($x, $expanded) {
    $y = 80
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $blue), $x, $y, 580, 400)
    $g.DrawString("Windows heeft uw pc beschermd", $fTitle, $white, $x + 24, $y + 22)
    $g.DrawString("Microsoft Defender SmartScreen heeft voorkomen dat een onbekende app werd gestart. Als u deze app uitvoert, loopt uw pc mogelijk risico.",
        $fText, $light, (New-Object System.Drawing.RectangleF(($x + 24), ($y + 74), 530, 60)))
    if ($expanded) {
        $g.DrawString("App:         OoitGedacht.exe", $fText, $light, $x + 24, $y + 150)
        $g.DrawString("Uitgever:   Onbekende uitgever", $fText, $light, $x + 24, $y + 176)
        Button ($x + 250) ($y + 340) "Toch uitvoeren"
        Button ($x + 412) ($y + 340) "Niet uitvoeren"
        Highlight ($x + 250) ($y + 340) 150 36 2 "top"
    } else {
        $g.DrawString("Meer info", $fLink, $white, $x + 24, $y + 136)
        Button ($x + 412) ($y + 340) "Niet uitvoeren"
        Highlight ($x + 22) ($y + 134) 78 24 1 "right"
    }
}

$g.DrawString("1.  Klik op ""Meer info""", $fStep, $white, 40, 22)
$g.DrawString("2.  Klik op ""Toch uitvoeren""", $fStep, $white, 680, 22)
Dialog 40 $false
Dialog 660 $true
$g.DrawString("Dit is normaal bij kleine programma's die (nog) niet bij Microsoft bekend zijn.", $fText, $light, 40, 510)

$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
