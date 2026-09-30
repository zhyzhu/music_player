$ErrorActionPreference = 'Continue'
$ProjectRoot = 'C:\Users\zhuzh\Documents\music_player'
$Jdk = Join-Path $ProjectRoot 'tools\jdk'
$Src = Join-Path $ProjectRoot 'app\src'
$Work = Join-Path $ProjectRoot 'build\id3-test'
$Fixtures = Join-Path $ProjectRoot 'build\id3-fixtures'

Write-Output "=== 1) build fixtures (independent Python writer) ==="
$py = 'C:\Users\zhuzh\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
& $py (Join-Path $ProjectRoot 'tools\make_id3_fixtures.py')
if ($LASTEXITCODE -ne 0) { Write-Output 'fixture generation failed'; exit 1 }

Write-Output ""
Write-Output "=== 2) write the test harness ==="
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$harness = Join-Path $Work 'Id3TestHarness.java'
$body = @'
import java.io.File;
import java.io.RandomAccessFile;

import com.example.musicplayer.Id3;

public class Id3TestHarness {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        String[] names = args[1].split(",");
        int failures = 0;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            boolean expectArt = Boolean.parseBoolean(args[2 + i * 2]);
            int expectedBytes = Integer.parseInt(args[3 + i * 2]);

            File mp3 = new File(dir, name + ".mp3");
            byte[] got = Id3.readAlbumArt(mp3);

            String verdict;
            boolean ok;
            if (!expectArt) {
                ok = (got == null);
                verdict = ok ? "PASS (no art, as expected)" : "FAIL (got " + got.length + " bytes)";
            } else if (got == null) {
                ok = false;
                verdict = "FAIL (no art extracted)";
            } else if (got.length != expectedBytes) {
                ok = false;
                verdict = "FAIL (length " + got.length + " != " + expectedBytes + ")";
            } else {
                byte[] want = readFile(new File(dir, name + ".expected"));
                ok = java.util.Arrays.equals(got, want);
                verdict = ok ? "PASS (" + got.length + " bytes, exact match)"
                             : "FAIL (bytes differ)";
            }
            if (!ok) {
                failures++;
            }
            System.out.println(String.format("  %-26s %s", name, verdict));
        }
        System.out.println();
        System.out.println(failures == 0
                ? "ALL ID3 TESTS PASSED"
                : (failures + " ID3 TEST(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static byte[] readFile(File file) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] data = new byte[(int) raf.length()];
            raf.readFully(data);
            return data;
        } finally {
            raf.close();
        }
    }
}
'@
[System.IO.File]::WriteAllText($harness, $body, (New-Object System.Text.UTF8Encoding($false)))

Write-Output "=== 3) compile the real Id3 class + harness with the desktop JDK ==="
$classesDir = Join-Path $Work 'classes'
Remove-Item $classesDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$errFile = Join-Path $Work 'javac.err'
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d $classesDir $harness `
    (Join-Path $Src 'com\example\musicplayer\Id3.java') 2>$errFile
$code = $LASTEXITCODE
$err = Get-Content $errFile -Raw -ErrorAction SilentlyContinue
if ($err -and $err.Trim().Length -gt 0) {
    ($err -split "`n") | Where-Object { $_.Trim() } | Select-Object -First 20 | ForEach-Object { Write-Output ("  " + $_.Trim()) }
}
if ($code -ne 0) { Write-Output "compile failed"; exit 1 }
Write-Output "  compiled"

Write-Output ""
Write-Output "=== 4) run the tests ==="
# Build the argument list straight from the manifest.
$manifest = (Get-Content (Join-Path $Fixtures 'manifest.json') -Raw) -replace '^\xEF\xBB\xBF', ''
$json = $manifest | ConvertFrom-Json
$names = @()
$flags = @()
foreach ($entry in $json) {
    $names += $entry.name
    $flags += ([string]$entry.expectArt).ToLower()
    if ($entry.expectArt) { $flags += [string]$entry.expectedBytes } else { $flags += '0' }
}
$testArgs = @($Fixtures, ($names -join ',')) + $flags
& (Join-Path $Jdk 'bin\java.exe') -cp $classesDir Id3TestHarness @testArgs 2>&1 | ForEach-Object { Write-Output $_ }
Write-Output ("`ntest exit=" + $LASTEXITCODE)
