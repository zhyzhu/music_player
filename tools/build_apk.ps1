$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------- configuration
$ProjectRoot = 'C:\Users\zhuzh\Documents\music_player'
$AppDir      = Join-Path $ProjectRoot 'app'
$ToolRoot    = Join-Path $ProjectRoot 'tools'
$Jdk         = Join-Path $ToolRoot 'jdk'
$Sdk         = Join-Path $ToolRoot 'sdk'
$BuildTools  = Join-Path $Sdk 'build-tools\25.0.3'
$Platform    = Join-Path $Sdk 'platforms\android-8'
$OutDir      = Join-Path $ProjectRoot 'build'
$ApkName     = 'MusicPlayer-1.0-android2.2.apk'

$Java       = Join-Path $Jdk 'bin\java.exe'
$Javac      = Join-Path $Jdk 'bin\javac.exe'
$Keytool    = Join-Path $Jdk 'bin\keytool.exe'
$JarSigner  = Join-Path $Jdk 'bin\jarsigner.exe'
$Aapt       = Join-Path $BuildTools 'aapt.exe'
$Dx         = Join-Path $BuildTools 'dx.bat'
$ZipAl      = Join-Path $BuildTools 'zipalign.exe'
$ApkSign    = Join-Path $BuildTools 'apksigner.bat'
$AndroidJar = Join-Path $Platform 'android.jar'

$LogDir = Join-Path $OutDir 'logs'

# dx.bat (and the other build-tools wrappers) locate Java through find_java.bat,
# which build-tools 25 no longer ships: without java on PATH they exit silently
# with status 0 and produce nothing. Put our JDK first for the whole build.
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH

function Step($text) {
    Write-Output ""
    Write-Output ("==> " + $text)
}

function Die($text) {
    Write-Output ("BUILD FAILED: " + $text)
    exit 1
}

# Invoke a native tool, capturing stdout/stderr to files. Native stderr must
# never reach PowerShell's error stream, or ErrorActionPreference would
# terminate the script on a mere warning. Returns the real process exit code.
$script:LastOut = ''
$script:LastErr = ''
function RunTool {
    param(
        [string]$Exe,
        [string[]]$ArgList,
        [string]$Tag
    )
    $outFile = Join-Path $LogDir ($Tag + '.out.txt')
    $errFile = Join-Path $LogDir ($Tag + '.err.txt')

    $captured = & $Exe @ArgList 2>$errFile
    $code = $LASTEXITCODE

    if ($null -ne $captured) {
        $captured | Set-Content -Path $outFile -Encoding UTF8
        $script:LastOut = ($captured | Out-String)
    } else {
        Set-Content -Path $outFile -Value '' -Encoding UTF8
        $script:LastOut = ''
    }

    if (Test-Path $errFile) {
        $raw = Get-Content $errFile -Raw
        if ($null -eq $raw) { $raw = '' }
        $script:LastErr = $raw
    } else {
        $script:LastErr = ''
    }
    return $code
}

function ShowLog($Tag, $MaxLines) {
    foreach ($kind in @('err', 'out')) {
        $f = Join-Path $LogDir ($Tag + '.' + $kind + '.txt')
        if (Test-Path $f) {
            $lines = @(Get-Content $f | Where-Object { $_.Trim().Length -gt 0 })
            if ($lines.Count -gt 0) {
                Write-Output ("  --- " + $Tag + "." + $kind + " ---")
                $lines | Select-Object -First $MaxLines | ForEach-Object { Write-Output ("  " + $_) }
                if ($lines.Count -gt $MaxLines) {
                    Write-Output ("  ... (" + ($lines.Count - $MaxLines) + " more lines in " + $f + ")")
                }
            }
        }
    }
}

# ------------------------------------------------------------- toolchain check
Step 'checking toolchain'
foreach ($tool in @($Java, $Javac, $Aapt, $Dx, $AndroidJar)) {
    if (-not (Test-Path $tool)) { Die ("missing required tool: " + $tool) }
}
Write-Output ("  javac       : " + $Javac)
Write-Output ("  aapt        : " + $Aapt)
Write-Output ("  dx          : " + $Dx)
Write-Output ("  android.jar : " + $AndroidJar)

# ------------------------------------------------------------- clean workspace
Step 'preparing output folders'
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
$GenDir     = Join-Path $OutDir 'gen'
$ClassesDir = Join-Path $OutDir 'classes'
$DexDir     = Join-Path $OutDir 'dex'
$ResPkgDir  = Join-Path $OutDir 'res-package'
foreach ($d in @($LogDir, $GenDir, $ClassesDir, $DexDir, $ResPkgDir)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

$manifest  = Join-Path $AppDir 'AndroidManifest.xml'
$resDir    = Join-Path $AppDir 'res'
$assetsDir = Join-Path $AppDir 'assets'

# ---------------------------------------------------- resources -> R.java + .ap_
Step 'aapt: generate R.java and package resources'
$resApk = Join-Path $ResPkgDir 'resources.ap_'
$code = RunTool -Exe $Aapt -Tag 'aapt-resources' -ArgList @(
    'package', '-f', '-m',
    '-M', $manifest,
    '-S', $resDir,
    '-J', $GenDir,
    '-F', $resApk,
    '-I', $AndroidJar
)
if ($code -ne 0) { ShowLog 'aapt-resources' 30; Die 'aapt resource packaging failed' }

$rjava = Join-Path $GenDir 'com\example\musicplayer\R.java'
if (-not (Test-Path $rjava)) { ShowLog 'aapt-resources' 30; Die 'aapt did not generate R.java' }
Write-Output "  R.java generated"

# ------------------------------------------------------------------ compilation
Step 'javac: compile against android.jar (Java 6 bytecode)'
$sources = @()
$sources += (Get-ChildItem -Path (Join-Path $AppDir 'src') -Filter '*.java' -Recurse).FullName
$sources += (Get-ChildItem -Path $GenDir -Filter '*.java' -Recurse).FullName
Write-Output ("  source files: " + $sources.Count)

$javacArgs = @(
    '-encoding', 'UTF-8',
    '-source', '1.6',
    '-target', '1.6',
    '-bootclasspath', $AndroidJar,
    '-classpath', $AndroidJar,
    '-d', $ClassesDir,
    '-nowarn'
)
$javacArgs += $sources

$code = RunTool -Exe $Javac -ArgList $javacArgs -Tag 'javac'
if ($code -ne 0) { ShowLog 'javac' 40; Die 'javac failed' }

$classFiles = @(Get-ChildItem -Path $ClassesDir -Filter '*.class' -Recurse)
if ($classFiles.Count -eq 0) { ShowLog 'javac' 40; Die 'javac produced no class files' }
$firstBytes = [byte[]][System.IO.File]::ReadAllBytes($classFiles[0].FullName)
$majorByte = [int]::Parse($firstBytes.GetValue(6).ToString())
Write-Output ("  class files: " + $classFiles.Count + "  (class file major version " + $majorByte + " => Java 6)")

# -------------------------------------------------------------------- dex step
Step 'dx: convert to Dalvik bytecode'
# dx.bat is unusable here: it resolves Java through find_java.bat, which
# build-tools 25 no longer ships, and then exits silently with status 0. Call
# dx.jar directly instead.
$DxJar = Join-Path $BuildTools 'lib\dx.jar'
if (-not (Test-Path $DxJar)) { Die ("missing dx.jar: " + $DxJar) }
$dexFile = Join-Path $DexDir 'classes.dex'
$code = RunTool -Exe $Java -Tag 'dx' -ArgList @(
    '-jar', $DxJar,
    '--dex',
    ('--output=' + $dexFile),
    $ClassesDir
)
if ($code -ne 0) { ShowLog 'dx' 40; Die 'dx failed' }
if (-not (Test-Path $dexFile)) { ShowLog 'dx' 40; Die 'dx produced no classes.dex' }
Write-Output ("  classes.dex: " + [math]::Round((Get-Item $dexFile).Length / 1KB, 1) + " KB")

# ------------------------------------------------------------------- assemble
Step 'aapt: package APK (resources + assets)'
$unsignedApk = Join-Path $OutDir 'app-unsigned.apk'
$code = RunTool -Exe $Aapt -Tag 'aapt-apk' -ArgList @(
    'package', '-f',
    '-M', $manifest,
    '-S', $resDir,
    '-A', $assetsDir,
    '-I', $AndroidJar,
    '-F', $unsignedApk
)
if ($code -ne 0) { ShowLog 'aapt-apk' 30; Die 'aapt APK packaging failed' }
Write-Output ("  unsigned APK: " + [math]::Round((Get-Item $unsignedApk).Length / 1KB, 1) + " KB")

# aapt cannot bundle a dex while packaging, so add classes.dex afterwards with
# "aapt add". Unlike a general-purpose zip rewrite this keeps existing entries
# (notably resources.arsc) at their original compression, which both zipalign
# and the Dalvik loader care about.
Write-Output "  adding classes.dex"
Copy-Item $dexFile (Join-Path $OutDir 'classes.dex') -Force
Push-Location $OutDir
try {
    $code = RunTool -Exe $Aapt -ArgList @('add', $unsignedApk, 'classes.dex') -Tag 'aapt-add-dex'
} finally {
    Pop-Location
}
if ($code -ne 0) { ShowLog 'aapt-add-dex' 30; Die 'failed to add classes.dex' }
Write-Output ("  APK with dex: " + [math]::Round((Get-Item $unsignedApk).Length / 1KB, 1) + " KB")

# ------------------------------------------------------------------- signing
Step 'signing APK'
$keyStore = Join-Path $ToolRoot 'debug.keystore'
if (-not (Test-Path $keyStore)) {
    Write-Output "  creating debug keystore"
    $code = RunTool -Exe $Keytool -Tag 'keytool' -ArgList @(
        '-genkeypair', '-v',
        '-keystore', $keyStore,
        '-alias', 'androiddebugkey',
        '-storepass', 'android', '-keypass', 'android',
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10950',
        '-dname', 'CN=Android Debug,O=Android,C=US'
    )
    if ($code -ne 0) { ShowLog 'keytool' 20; Die 'keytool failed' }
    Write-Output "  keystore created"
}

$signedApk = Join-Path $OutDir 'app-signed.apk'
$ApkSignJar = Join-Path $BuildTools 'lib\apksigner.jar'

if (Test-Path $ApkSignJar) {
    # apksigner.bat has the same missing find_java.bat defect as dx.bat, so the
    # jar is invoked directly. v2 signing is off: Android 2.2 only understands
    # the v1 (JAR) signature scheme.
    Write-Output "  signer: apksigner (v1 scheme only, for Android 2.2)"
    $code = RunTool -Exe $Java -Tag 'apksigner' -ArgList @(
        '-jar', $ApkSignJar,
        'sign',
        '--ks', $keyStore,
        '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android',
        '--ks-key-alias', 'androiddebugkey',
        '--v1-signing-enabled', 'true',
        '--v2-signing-enabled', 'false',
        '--out', $signedApk,
        $unsignedApk
    )
    if ($code -ne 0) { ShowLog 'apksigner' 30; Die 'apksigner failed' }
} elseif (Test-Path $JarSigner) {
    Write-Output "  signer: jarsigner (SHA1withRSA, for Android 2.2)"
    Copy-Item $unsignedApk $signedApk -Force
    $code = RunTool -Exe $JarSigner -Tag 'jarsigner' -ArgList @(
        '-keystore', $keyStore, '-storepass', 'android', '-keypass', 'android',
        '-sigalg', 'SHA1withRSA', '-digestalg', 'SHA1',
        $signedApk, 'androiddebugkey'
    )
    if ($code -ne 0) { ShowLog 'jarsigner' 30; Die 'jarsigner failed' }
} else {
    Die 'no APK signer available'
}

if (-not (Test-Path $signedApk)) { Die 'signing produced no APK' }
Write-Output ("  signed: " + [math]::Round((Get-Item $signedApk).Length / 1KB, 1) + " KB")

# ------------------------------------------------------------------ zipalign
Step 'zipalign (4-byte alignment)'
$finalApk = Join-Path $ProjectRoot $ApkName
if (Test-Path $ZipAl) {
    $code = RunTool -Exe $ZipAl -ArgList @('-f', '4', $signedApk, $finalApk) -Tag 'zipalign'
    if ($code -ne 0) { ShowLog 'zipalign' 20; Die 'zipalign failed' }
} else {
    Write-Output "  zipalign not found; copying signed APK"
    Copy-Item $signedApk $finalApk -Force
}

Write-Output ""
Write-Output "=== BUILD OK ==="
Write-Output ("apk  : " + $finalApk)
Write-Output ("size : " + [math]::Round((Get-Item $finalApk).Length / 1KB, 1) + " KB")
