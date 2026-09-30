# Run this OUTSIDE the agent sandbox, in a normal PowerShell window.
# It lists every currently attached Android/ADB USB device with its driver
# status, so we can tell "not plugged in" apart from "plugged in, no driver".

Write-Host "=== Android / ADB USB devices currently attached ===" -ForegroundColor Cyan

$found = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
    Where-Object {
        $_.InstanceId -match 'VID_18D1|VID_0BB4|VID_04E8|VID_22B8|VID_1004|VID_12D1|VID_05C6|VID_0FCE|VID_0489|VID_1EBF' -or
        $_.FriendlyName -match 'ADB|Android|Bootloader'
    }

if (-not $found) {
    Write-Host "  NOTHING FOUND - Windows does not see an Android device at all." -ForegroundColor Yellow
    Write-Host "  => cable / port / phone-side problem, or the phone is not in a debug-capable USB mode."
} else {
    $found | Select-Object Status, Class, FriendlyName, InstanceId |
        Format-Table -AutoSize | Out-String -Width 250 | Write-Host

    $bad = $found | Where-Object { $_.Status -ne 'OK' }
    if ($bad) {
        Write-Host "  => device present but NOT working: missing/incorrect USB driver." -ForegroundColor Yellow
    } else {
        Write-Host "  => device present and OK." -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "=== problem devices with an error code (any device) ===" -ForegroundColor Cyan
Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
    Where-Object { $_.Status -ne 'OK' } |
    Select-Object Status, Class, FriendlyName | Format-Table -AutoSize | Out-String -Width 250 | Write-Host

Write-Host "=== adb view ===" -ForegroundColor Cyan
& adb devices -l
