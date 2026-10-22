<#
.SYNOPSIS
    Prépare (et, avec -Publish, publie) une release de Football Rama.

.DESCRIPTION
    1. Vérifie que l'arbre Git est propre, sur main, et que HEAD est déjà poussé sur origin/main.
    2. Vérifie que android/gradle.properties contient APP_VERSION_NAME=<Version> et un APP_VERSION_CODE
       supérieur à celui de publisher/app_release.json.
    3. Lance gradlew assembleRelease (signature : FOOTBALL_RAMA_SIGNING ou %USERPROFILE%\.football-rama\signing.properties).
    4. Vérifie avec apksigner que l'APK est signé (schémas v2 et v3), affiche l'empreinte SHA-256 du certificat et la
       compare à %USERPROFILE%\.football-rama\certificate-sha256.txt ; vérifie paquet et version avec aapt2.
    5. Copie l'APK en dist/MatchsEtChaines-<Version>.apk, calcule SHA-256 et taille, met à jour publisher/app_release.json
       et le valide avec le publisher.
    6. S'ARRÊTE en affichant les commandes restantes (release GitHub, commit, push, workflow), sauf avec -Publish.

.EXAMPLE
    .\scripts\publish_release.ps1 -Version 1.0.1 -Notes "Corrections et améliorations."
    .\scripts\publish_release.ps1 -Version 1.0.1 -Notes "Corrections et améliorations." -Publish
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [ValidateLength(0, 500)]
    [string]$Notes = '',

    [switch]$Publish
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$message) {
    Write-Host "ERREUR : $message" -ForegroundColor Red
    exit 1
}

function Invoke-Native([string]$exe, [string[]]$arguments) {
    # PowerShell 5.1 : avec 'Stop', une simple ligne sur stderr d'un exécutable deviendrait une exception.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $exe @arguments 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($LASTEXITCODE -ne 0) { Fail "$exe $($arguments -join ' ') a échoué (code $LASTEXITCODE).`n$($out -join "`n")" }
    return $out
}

$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Tag = "v$Version"
$ApkName = "MatchsEtChaines-$Version.apk"
$ManifestPath = Join-Path $Repo 'publisher\app_release.json'
$ApkUrl = "https://github.com/davidjahan720/football-rama/releases/download/$Tag/$ApkName"
if (-not $Notes) { $Notes = "Football Rama $Version" }

Push-Location $Repo
try {
    # --- 1. Git : propre, sur main, poussé -------------------------------------------------------------
    $branch = (Invoke-Native git @('rev-parse', '--abbrev-ref', 'HEAD') | Select-Object -First 1).Trim()
    if ($branch -ne 'main') { Fail "branche courante '$branch' : placez-vous sur main." }
    $dirty = Invoke-Native git @('status', '--porcelain')
    if ($dirty) { Fail "l'arbre Git n'est pas propre :`n$($dirty -join "`n")" }
    Invoke-Native git @('fetch', '--quiet', 'origin', 'main') | Out-Null
    $head = (Invoke-Native git @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
    $remote = (Invoke-Native git @('rev-parse', 'origin/main') | Select-Object -First 1).Trim()
    if ($head -ne $remote) { Fail 'HEAD differe de origin/main : poussez (git push) ou mettez a jour (git pull) avant la release.' }
    $tagExists = & git ls-remote --tags origin "refs/tags/$Tag"
    if ($tagExists) { Fail "le tag $Tag existe déjà sur GitHub : choisissez une nouvelle version." }

    # --- 2. Version ------------------------------------------------------------------------------------
    $gradleProps = Get-Content (Join-Path $Repo 'android\gradle.properties')
    $vnLine = $gradleProps | Where-Object { $_ -match '^APP_VERSION_NAME=' } | Select-Object -First 1
    $vcLine = $gradleProps | Where-Object { $_ -match '^APP_VERSION_CODE=' } | Select-Object -First 1
    if (-not $vnLine -or -not $vcLine) { Fail 'APP_VERSION_NAME / APP_VERSION_CODE absents de android/gradle.properties.' }
    $versionName = ($vnLine -replace '^APP_VERSION_NAME=', '').Trim()
    $versionCode = [int](($vcLine -replace '^APP_VERSION_CODE=', '').Trim())
    if ($versionName -ne $Version) {
        Fail "android/gradle.properties contient APP_VERSION_NAME=$versionName. Mettez APP_VERSION_NAME=$Version, incrémentez APP_VERSION_CODE, committez, poussez, puis relancez."
    }
    $previous = Get-Content -Raw -Encoding UTF8 $ManifestPath | ConvertFrom-Json
    if ($previous.sha256 -and $versionCode -le [int]$previous.version_code) {
        Fail "APP_VERSION_CODE ($versionCode) doit être supérieur à la version publiée ($($previous.version_code))."
    }

    # --- 3. Signature configurée, build ------------------------------------------------------------------
    $signing = if ($env:FOOTBALL_RAMA_SIGNING) { $env:FOOTBALL_RAMA_SIGNING } else { Join-Path $env:USERPROFILE '.football-rama\signing.properties' }
    if (-not (Test-Path $signing)) { Fail "configuration de signature introuvable ($signing). Lancez d'abord scripts\create_release_keystore.ps1." }

    Push-Location (Join-Path $Repo 'android')
    try {
        & .\gradlew.bat --console=plain clean :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { Fail "gradlew assembleRelease a échoué (code $LASTEXITCODE)." }
    } finally { Pop-Location }

    $apk = Join-Path $Repo 'android\app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $apk)) { Fail "APK signé introuvable ($apk) : le build a produit un APK non signé ?" }

    # --- 4. apksigner / aapt2 ----------------------------------------------------------------------------
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
        Sort-Object { try { [version]($_.Name -replace '[^0-9.].*$', '') } catch { [version]'0.0' } } -Descending |
        Select-Object -First 1
    if (-not $buildTools) { Fail "build-tools Android introuvables dans $sdk." }
    $apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
    $aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'

    $verify = Invoke-Native $apksigner @('verify', '--verbose', '--print-certs', $apk)
    if (-not ($verify -match 'Verified using v2 scheme \(APK Signature Scheme v2\): true')) { Fail "schéma de signature v2 absent.`n$($verify -join "`n")" }
    if (-not ($verify -match 'Verified using v3 scheme \(APK Signature Scheme v3\): true')) { Fail "schéma de signature v3 absent.`n$($verify -join "`n")" }
    $digestLine = $verify | Where-Object { $_ -match 'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})' } | Select-Object -First 1
    if (-not $digestLine) { Fail 'empreinte du certificat introuvable dans la sortie apksigner.' }
    $null = $digestLine -match 'SHA-256 digest: ([0-9a-f]{64})'
    $certSha = $Matches[1].ToLowerInvariant()
    $dnLine = $verify | Where-Object { $_ -match 'Signer #1 certificate DN:' } | Select-Object -First 1
    Write-Host "Certificat de signature : $dnLine" -ForegroundColor Green
    Write-Host "Empreinte SHA-256      : $certSha" -ForegroundColor Green

    $expectedFile = Join-Path $env:USERPROFILE '.football-rama\certificate-sha256.txt'
    if (Test-Path $expectedFile) {
        $expected = ((Get-Content -Raw $expectedFile) -replace '[^0-9A-Fa-f]', '').ToLowerInvariant()
        if ($expected -ne $certSha) { Fail "le certificat ne correspond PAS à $expectedFile : APK signé avec une autre clé, les boîtiers refuseraient la mise à jour." }
        Write-Host 'Empreinte conforme à certificate-sha256.txt.' -ForegroundColor Green
    } else {
        Write-Host "ATTENTION : $expectedFile absent, empreinte non comparée." -ForegroundColor Yellow
    }

    if (Test-Path $aapt2) {
        $badging = (Invoke-Native $aapt2 @('dump', 'badging', $apk) | Select-Object -First 1)
        $expectedBadging = "package: name='fr.footballrama.app' versionCode='$versionCode' versionName='$Version'"
        if (-not $badging.StartsWith($expectedBadging)) { Fail "paquet ou version inattendus : $badging" }
    }

    # --- 5. dist/, SHA-256, taille, manifeste ------------------------------------------------------------
    $dist = Join-Path $Repo 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $distApk = Join-Path $dist $ApkName
    Copy-Item -Force $apk $distApk
    $sha256 = (Get-FileHash -Algorithm SHA256 $distApk).Hash.ToLowerInvariant()
    $size = (Get-Item $distApk).Length
    if ($size -gt 100MB) { Fail "APK trop volumineux ($size octets, 100 Mo maximum)." }

    $notesJson = ConvertTo-Json -InputObject $Notes -Compress
    $manifest = @"
{
  "schema": 1,
  "version_code": $versionCode,
  "version_name": "$Version",
  "apk_url": "$ApkUrl",
  "sha256": "$sha256",
  "size": $size,
  "min_sdk": 26,
  "notes": $notesJson
}
"@
    # UTF-8 SANS BOM (le publisher refuse un BOM), fins de ligne LF.
    [System.IO.File]::WriteAllText($ManifestPath, ($manifest -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))

    $python = Join-Path $Repo 'publisher\.venv\Scripts\python.exe'
    if (-not (Test-Path $python)) { $python = 'py' }
    Push-Location (Join-Path $Repo 'publisher')
    try {
        & $python -m football_rama_publisher check-update --manifest app_release.json
        if ($LASTEXITCODE -ne 0) { Fail 'publisher/app_release.json refusé par le publisher (voir ci-dessus).' }
    } finally { Pop-Location }

    Write-Host ''
    Write-Host "APK      : $distApk" -ForegroundColor Green
    Write-Host "SHA-256  : $sha256"
    Write-Host "Taille   : $size octets"
    Write-Host "Manifeste: $ManifestPath mis à jour"
    Write-Host ''

    # Ordre important : la release (et donc l'APK) doit exister AVANT que update.json ne l'annonce.
    $title = "Football Rama $Version"
    $commitMessage = "Release $Version : manifeste de mise a jour"
    $steps = @(
        @{ Exe = 'gh'; Args = @('release', 'create', $Tag, $distApk, '--target', $head, '--title', $title, '--notes', $Notes) },
        @{ Exe = 'git'; Args = @('add', 'publisher/app_release.json') },
        @{ Exe = 'git'; Args = @('commit', '-m', $commitMessage) },
        @{ Exe = 'git'; Args = @('push', 'origin', 'main') },
        @{ Exe = 'gh'; Args = @('workflow', 'run', 'publish.yml', '--ref', 'main') }
    )

    if (-not $Publish) {
        Write-Host 'Arrêt avant publication. Commandes restantes (dans cet ordre) :' -ForegroundColor Yellow
        foreach ($s in $steps) {
            $quoted = $s.Args | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }
            Write-Host ("  {0} {1}" -f $s.Exe, ($quoted -join ' '))
        }
        Write-Host ''
        Write-Host 'Ou relancez ce script avec -Publish pour les exécuter.' -ForegroundColor Yellow
        exit 0
    }

    foreach ($s in $steps) {
        Write-Host ("> {0} {1}" -f $s.Exe, ($s.Args -join ' ')) -ForegroundColor Cyan
        & $s.Exe @($s.Args)
        if ($LASTEXITCODE -ne 0) { Fail "$($s.Exe) a échoué (code $LASTEXITCODE). Reprenez manuellement à partir de cette étape." }
    }
    Write-Host ''
    Write-Host "Release $Version publiée. update.json sera en ligne à la fin du workflow « Publish fixtures »." -ForegroundColor Green
} finally {
    Pop-Location
}
