$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = 'C:\Users\zhuzh\Documents\music_player\tools'
$dl   = Join-Path $root 'downloads'
New-Item -ItemType Directory -Force -Path $dl | Out-Null

$items = @(
  @{ Name = 'jdk8';  Url = 'https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jdk_x64_windows_hotspot_8u504b01.zip'; Zip = 'jdk8.zip' },
  @{ Name = 'sdk';   Url = 'https://dl.google.com/android/repository/build-tools_r25.0.3-windows.zip';                                                Zip = 'buildtools.zip' },
  @{ Name = 'plat8'; Url = 'https://dl.google.com/android/repository/android-2.2_r03.zip';                                                              Zip = 'platform8.zip' }
)

foreach ($it in $items) {
  $zip = Join-Path $dl $it.Zip
  if (Test-Path $zip) {
    Write-Output ("[skip] already downloaded: " + $it.Zip)
    continue
  }
  Write-Output ("[get ] " + $it.Url)
  & curl.exe -L --fail --retry 3 --retry-delay 3 -sS -o $zip $it.Url
  if ($LASTEXITCODE -ne 0) { throw ("download failed: " + $it.Url) }
  $len = (Get-Item $zip).Length
  Write-Output ("[ok  ] " + $it.Zip + "  " + [math]::Round($len / 1MB, 1) + " MB")
}

Write-Output "=== extracting ==="
New-Item -ItemType Directory -Force -Path (Join-Path $root 'jdk') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root 'sdk\build-tools') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root 'sdk\platforms') | Out-Null

if (-not (Test-Path (Join-Path $root 'jdk\bin\javac.exe'))) {
  & tar.exe -xf (Join-Path $dl 'jdk8.zip') -C (Join-Path $root 'jdk')
  $inner = Get-ChildItem (Join-Path $root 'jdk') -Directory | Select-Object -First 1
  if ($inner -and -not (Test-Path (Join-Path $root 'jdk\bin'))) {
    Get-ChildItem $inner.FullName | Move-Item -Destination (Join-Path $root 'jdk') -Force
    Remove-Item $inner.FullName -Recurse -Force
  }
  Write-Output "[ok  ] jdk extracted"
}

if (-not (Test-Path (Join-Path $root 'sdk\build-tools\25.0.3\aapt.exe'))) {
  & tar.exe -xf (Join-Path $dl 'buildtools.zip') -C (Join-Path $root 'sdk\build-tools')
  # archive may unpack as android-7.0.0 or similar; normalise to 25.0.3
  $bt = Join-Path $root 'sdk\build-tools'
  if (-not (Test-Path (Join-Path $bt '25.0.3'))) {
    $inner = Get-ChildItem $bt -Directory | Select-Object -First 1
    if ($inner) { Rename-Item $inner.FullName '25.0.3' }
  }
  Write-Output "[ok  ] build-tools extracted"
}

if (-not (Test-Path (Join-Path $root 'sdk\platforms\android-8\android.jar'))) {
  & tar.exe -xf (Join-Path $dl 'platform8.zip') -C (Join-Path $root 'sdk\platforms')
  $pf = Join-Path $root 'sdk\platforms'
  if (-not (Test-Path (Join-Path $pf 'android-8'))) {
    $inner = Get-ChildItem $pf -Directory | Where-Object { $_.Name -notmatch '^android-8$' } | Select-Object -First 1
    if ($inner) { Rename-Item $inner.FullName 'android-8' }
  }
  Write-Output "[ok  ] platform 8 extracted"
}

Write-Output "=== verification ==="
foreach ($p in @('jdk\bin\javac.exe', 'jdk\bin\java.exe', 'jdk\bin\keytool.exe', 'sdk\build-tools\25.0.3\aapt.exe', 'sdk\build-tools\25.0.3\dx.bat', 'sdk\build-tools\25.0.3\zipalign.exe', 'sdk\build-tools\25.0.3\apksigner.bat', 'sdk\platforms\android-8\android.jar')) {
  $full = Join-Path $root $p
  if (Test-Path $full) { Write-Output ("  OK      " + $p) } else { Write-Output ("  MISSING " + $p) }
}
Write-Output "=== build-tools dir listing ==="
Get-ChildItem (Join-Path $root 'sdk\build-tools\25.0.3') | Select-Object -ExpandProperty Name
Write-Output "DONE"
