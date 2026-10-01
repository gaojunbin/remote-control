# Launches the built Windows app on the CI runner's desktop against its own demo, photographs the
# screen once the window is up, and fails if the process died. The runner is the only Windows the
# app is checked on, so this picture is the evidence that it opens there.
param([Parameter(Mandatory = $true)][string]$OutDir)
$ErrorActionPreference = 'Stop'

$exe = Get-ChildItem -Path 'app/build/compose/binaries/main/app' -Filter 'Remote Control.exe' -Recurse |
  Select-Object -First 1
if (-not $exe) { throw 'No Remote Control.exe under app/build/compose/binaries/main/app' }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$app = Start-Process -FilePath $exe.FullName -ArgumentList '--demo', '--ephemeral' -PassThru
Start-Sleep -Seconds 20
if ($app.HasExited) { throw "The app exited during launch with code $($app.ExitCode)" }

Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
$bitmap.Save((Join-Path $OutDir 'launch.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose(); $bitmap.Dispose()

Stop-Process -Id $app.Id -Force
Write-Output "launched $($exe.FullName); screenshot in $OutDir/launch.png"
