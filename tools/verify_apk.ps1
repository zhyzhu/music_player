$ErrorActionPreference = 'Continue'
$ProjectRoot = 'C:\Users\zhuzh\Documents\music_player'
$Jdk = Join-Path $ProjectRoot 'tools\jdk'
$BuildTools = Join-Path $ProjectRoot 'tools\sdk\build-tools\25.0.3'
$Java = Join-Path $Jdk 'bin\java.exe'
$Aapt = Join-Path $BuildTools 'aapt.exe'
$ZipAl = Join-Path $BuildTools 'zipalign.exe'
$ApkSignJar = Join-Path $BuildTools 'lib\apksigner.jar'
$Apk = Join-Path $ProjectRoot 'MusicPlayer-1.0-android2.2.apk'
$Tmp = Join-Path $ProjectRoot 'build\verify'
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null

function Run($exe, $argList, $tag) {
    $e = Join-Path $Tmp ($tag + '.err')
    $o = & $exe @argList 2>$e
    return @{ Out = ($o | Out-String); Err = (Get-Content $e -Raw -ErrorAction SilentlyContinue); Code = $LASTEXITCODE }
}

Write-Output ("APK: " + $Apk)
Write-Output ("Size: " + [math]::Round((Get-Item $Apk).Length / 1KB, 2) + " KB")
Write-Output ("SHA256: " + (Get-FileHash $Apk -Algorithm SHA256).Hash)

Write-Output ""
Write-Output "=== aapt dump badging ==="
$r = Run $Aapt @('dump', 'badging', $Apk) 'badging'
Write-Output $r.Out
if ($r.Err) { Write-Output ("stderr: " + $r.Err) }

Write-Output "=== aapt dump permissions ==="
$r = Run $Aapt @('dump', 'permissions', $Apk) 'perms'
Write-Output $r.Out

Write-Output "=== aapt list (entries) ==="
$r = Run $Aapt @('list', $Apk) 'list'
Write-Output $r.Out

Write-Output "=== apksigner verify (verbose) ==="
$r = Run $Java @('-jar', $ApkSignJar, 'verify', '--verbose', '--print-certs', $Apk) 'verify'
Write-Output $r.Out
if ($r.Err) { Write-Output ("stderr: " + $r.Err) }
Write-Output ("verify exit code: " + $r.Code)

Write-Output ""
Write-Output "=== zipalign check (-c 4) ==="
$r = Run $ZipAl @('-c', '-v', '4', $Apk) 'zipalign-check'
Write-Output ($r.Out -split "`n" | Select-Object -Last 6 | Out-String)
if ($r.Err) { Write-Output ("stderr: " + $r.Err) }
Write-Output ("zipalign exit code: " + $r.Code)
