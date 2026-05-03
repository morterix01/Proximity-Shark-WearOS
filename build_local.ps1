# ─── ProximityShark WearOS v1.1.0 — Script build locale ─────────────────────────────
# Esegui questo script in PowerShell dalla root del progetto
# Richiede: JDK 17+ installato e configurato, e Android SDK.

$version = "1.1.0"
$outDir = "RELEASES\ProximityShark_v$version"

Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║  🦈⌚ ProximityShark WearOS v$version — Build Locale      ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# ── Crea cartella output ───────────────────────────────────────────────────────
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Write-Host "📁 Output: $outDir" -ForegroundColor Cyan
Write-Host ""

# ── Build Android APK ──────────────────────────────────────────────────
Write-Host "🔨 Build Wear OS APK (release)..." -ForegroundColor Yellow
.\gradlew assembleRelease --no-daemon

if ($LASTEXITCODE -ne 0) { Write-Host "❌ Build Wear OS fallita" -ForegroundColor Red; exit 1 }

$apkSrc = "app\build\outputs\apk\release\app-release.apk"
$apkDst = "$outDir\ProximityShark_v${version}_OS.apk"
Copy-Item $apkSrc $apkDst
Write-Host "✅ APK Wear OS → $apkDst" -ForegroundColor Green

# ── Riepilogo ──────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║  ✅ Build completata!                                 ║" -ForegroundColor Green
Write-Host "╚══════════════════════════════════════════════════════╝" -ForegroundColor Green
Write-Host ""
Write-Host "File prodotti:" -ForegroundColor White
Get-ChildItem $outDir | ForEach-Object {
    $size = [math]::Round($_.Length / 1MB, 1)
    Write-Host "  📦 $($_.Name)  ($size MB)" -ForegroundColor Cyan
}
Write-Host ""
