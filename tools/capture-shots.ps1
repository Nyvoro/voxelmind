<#
.SYNOPSIS
    Photographs the Minecraft window whenever the client smoke logs "VML-SMOKE shot <name>".

.DESCRIPTION
    Started in the background by tools/smoke-client.sh when SMOKE_SHOTS=1. It watches the Gradle log of
    the smoke run, and for every "VML-SMOKE shot <name>" line copies the visible Minecraft window from the
    screen into <OutDir>/NN-<name>.png. The window must be visible (not minimised, not covered). Ends after
    the "VML-SMOKE done" line or after -TimeoutSeconds.
#>
param(
    [Parameter(Mandatory = $true)][string]$Log,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [int]$TimeoutSeconds = 600
)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class VmlWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
"@
[void][VmlWin]::SetProcessDPIAware()
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$taken = 0
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $Log) {
        $text = Get-Content -LiteralPath $Log -ErrorAction SilentlyContinue
        $shots = @($text | Select-String -Pattern 'VML-SMOKE shot (.+)$')
        while ($taken -lt $shots.Count) {
            $name = $shots[$taken].Matches[0].Groups[1].Value.Trim()
            $window = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
            if ($window) {
                $rect = New-Object VmlWin+RECT
                [void][VmlWin]::GetWindowRect($window.MainWindowHandle, [ref]$rect)
                $w = $rect.Right - $rect.Left
                $h = $rect.Bottom - $rect.Top
                if ($w -gt 100 -and $h -gt 100) {
                    $bitmap = New-Object System.Drawing.Bitmap $w, $h
                    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
                    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
                    $safe = ($name -replace '[^A-Za-z0-9 _-]', '').Trim() -replace ' +', '-'
                    $file = Join-Path $OutDir ('{0:D2}-{1}.png' -f ($taken + 1), $safe)
                    $bitmap.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
                    $graphics.Dispose()
                    $bitmap.Dispose()
                }
            }
            $taken++
        }
        if ($text -match 'VML-SMOKE done') { break }
    }
    Start-Sleep -Milliseconds 250
}
