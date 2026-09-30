$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Install a portable, self-contained Git (MinGit) inside the workspace.
# No admin rights, no system-wide changes.
$root = 'C:\Users\zhuzh\Documents\music_player'
$tools = Join-Path $root 'tools'
$dest = Join-Path $tools 'mingit'
$zip = Join-Path $tools 'downloads\mingit.zip'

# github.com release downloads are frequently reset from this network, so go
# through a mirror that supports range requests (enables resuming).
$origin = 'https://github.com/git-for-windows/git/releases/download/v2.56.0.windows.1/MinGit-2.56.0-64-bit.zip'
$mirrors = @(
    ('https://ghproxy.net/' + $origin),
    ('https://gh-proxy.com/' + $origin),
    $origin
)

New-Item -ItemType Directory -Force -Path (Join-Path $tools 'downloads') | Out-Null

$haveZip = (Test-Path $zip) -and ((Get-Item $zip).Length -gt 30MB)
if (-not $haveZip) {
    foreach ($url in $mirrors) {
        Write-Output ("downloading MinGit from " + ($url -split '/')[2] + " ...")
        & curl.exe -L --fail --retry 5 --retry-delay 2 --retry-all-errors -C - -sS -o $zip $url
        if ($LASTEXITCODE -eq 0 -and (Test-Path $zip) -and ((Get-Item $zip).Length -gt 30MB)) {
            Write-Output ("  ok: " + [math]::Round((Get-Item $zip).Length / 1MB, 1) + " MB")
            $haveZip = $true
            break
        }
        Write-Output "  failed, trying next mirror"
        if (Test-Path $zip) { Remove-Item $zip -Force }
    }
} else {
    Write-Output "using cached mingit.zip"
}
if (-not $haveZip) { throw 'could not download MinGit from any mirror' }

if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
& tar.exe -xf $zip -C $dest
if ($LASTEXITCODE -ne 0) { throw 'MinGit extraction failed' }

$git = Join-Path $dest 'cmd\git.exe'
if (-not (Test-Path $git)) { throw ("git.exe not found at " + $git) }

Write-Output "=== version ==="
& $git --version

Write-Output "=== bundled CA bundle? ==="
$ca = Join-Path $dest 'mingw64\ssl\certs\ca-bundle.crt'
$ca2 = Join-Path $dest 'ssl\certs\ca-bundle.crt'
if (Test-Path $ca) { Write-Output ("  found: " + $ca) }
elseif (Test-Path $ca2) { Write-Output ("  found: " + $ca2) }
else { Write-Output "  NOT bundled" }

Write-Output "=== mingit top level ==="
Get-ChildItem $dest | Select-Object -ExpandProperty Name
