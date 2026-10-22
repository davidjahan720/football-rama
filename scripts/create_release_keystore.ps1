<#
.SYNOPSIS
    Crée la clé de signature release de Football Rama (à lancer UNE SEULE FOIS, par David, sur son PC).

.DESCRIPTION
    - Crée %USERPROFILE%\.football-rama\release.jks (PKCS12, RSA 4096, validité 30 ans, alias footballrama,
      DN "CN=Football Rama") et signing.properties à côté (lu par android/app/build.gradle.kts).
    - Refuse d'écraser un keystore ou un signing.properties existant.
    - Restreint les droits du dossier à l'utilisateur courant (icacls).
    - Affiche l'empreinte SHA-256 du certificat et l'enregistre dans certificate-sha256.txt (non secret),
      que scripts/publish_release.ps1 compare à chaque release.

    Le mot de passe est saisi de façon masquée puis transmis à keytool par une variable d'environnement
    du processus (-storepass:env), jamais en argument de ligne de commande ni dans un journal.
    Format PKCS12 : le mot de passe de la clé est identique à celui du keystore.

.NOTES
    PERTE DU KEYSTORE OU DU MOT DE PASSE = PLUS AUCUNE MISE À JOUR POSSIBLE pour les boîtiers déjà installés
    (Android refuse une mise à jour signée par une autre clé : il faudrait désinstaller puis réinstaller).
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Dir = Join-Path $env:USERPROFILE '.football-rama'
$Jks = Join-Path $Dir 'release.jks'
$Props = Join-Path $Dir 'signing.properties'
$FingerprintFile = Join-Path $Dir 'certificate-sha256.txt'
$Alias = 'footballrama'
$ValidityDays = 10958   # 30 ans
$PassEnv = 'FOOTBALL_RAMA_KS_PASS'

function Find-Keytool {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
    $cmd = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    $candidates += 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    throw 'keytool introuvable : installez un JDK 17 (ou définissez JAVA_HOME).'
}

function ConvertTo-PropertiesValue([string]$value) {
    # Échappement java.util.Properties : \ = : # ! espaces, et \uXXXX hors ASCII imprimable.
    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $value.Length; $i++) {
        $c = $value[$i]
        $code = [int]$c
        if ($c -eq '\') { [void]$sb.Append('\\') }
        elseif ('=:#!'.Contains([string]$c)) { [void]$sb.Append('\').Append($c) }
        elseif ($c -eq ' ') { [void]$sb.Append('\ ') }
        elseif ($code -lt 0x20 -or $code -gt 0x7E) { [void]$sb.Append(('\u{0:x4}' -f $code)) }
        else { [void]$sb.Append($c) }
    }
    return $sb.ToString()
}

function Read-Password {
    while ($true) {
        $p1 = Read-Host 'Mot de passe du keystore (12 caractères minimum)' -AsSecureString
        $p2 = Read-Host 'Confirmez le mot de passe' -AsSecureString
        $b1 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($p1)
        $b2 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($p2)
        try {
            $s1 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b1)
            $s2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b2)
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b1)
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b2)
        }
        if ($s1 -cne $s2) { Write-Host 'Les deux saisies sont différentes, recommencez.' -ForegroundColor Yellow; continue }
        if ($s1.Length -lt 12) { Write-Host 'Mot de passe trop court (12 caractères minimum).' -ForegroundColor Yellow; continue }
        return $s1
    }
}

# 1. Jamais d'écrasement.
if ((Test-Path $Jks) -or (Test-Path $Props)) {
    Write-Host "Refus : $Jks ou $Props existe déjà. Un keystore existant ne doit JAMAIS être remplacé (les boîtiers ne pourraient plus être mis à jour)." -ForegroundColor Red
    exit 1
}

$keytool = Find-Keytool

# 2. Dossier réservé à l'utilisateur courant (avant de créer les fichiers, qui héritent de ces droits).
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $Dir /inheritance:r /grant:r "*${sid}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "icacls a échoué ($LASTEXITCODE) : droits du dossier non restreints, arrêt." }
foreach ($g in @('*S-1-1-0', '*S-1-5-11', '*S-1-5-32-545')) {   # Tout le monde, Utilisateurs authentifiés, Utilisateurs
    & icacls.exe $Dir /remove:g $g | Out-Null
}

# 3. Génération (mot de passe transmis par variable d'environnement du processus uniquement).
$password = Read-Password
$created = $false
try {
    [Environment]::SetEnvironmentVariable($PassEnv, $password, 'Process')
    & $keytool -genkeypair -v -storetype PKCS12 -keystore $Jks -alias $Alias -keyalg RSA -keysize 4096 `
        -validity $ValidityDays -dname 'CN=Football Rama' -storepass:env $PassEnv -keypass:env $PassEnv
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Jks)) { throw "keytool a échoué ($LASTEXITCODE)." }

    $escaped = ConvertTo-PropertiesValue $password
    $content = @(
        '# Football Rama - signature release. NE JAMAIS COMMITTER, NE JAMAIS PARTAGER.',
        '# Lu par android/app/build.gradle.kts (ou chemin donne par FOOTBALL_RAMA_SIGNING).',
        'storeFile=release.jks',
        "storePassword=$escaped",
        "keyAlias=$Alias",
        "keyPassword=$escaped"
    ) -join "`n"
    [System.IO.File]::WriteAllText($Props, $content + "`n", (New-Object System.Text.ASCIIEncoding))

    $list = & $keytool -list -v -keystore $Jks -alias $Alias -storepass:env $PassEnv
    if ($LASTEXITCODE -ne 0) { throw 'keytool -list a échoué.' }
    $fpPattern = 'SHA-?\s?256\s*:\s*([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){31})'
    $line = $list | Where-Object { $_ -match $fpPattern } | Select-Object -First 1
    if (-not $line) { throw 'Empreinte SHA-256 introuvable dans la sortie de keytool.' }
    $null = $line -match $fpPattern
    $fingerprint = $Matches[1].ToUpperInvariant()
    [System.IO.File]::WriteAllText($FingerprintFile, $fingerprint + "`n", (New-Object System.Text.ASCIIEncoding))
    $created = $true
} finally {
    [Environment]::SetEnvironmentVariable($PassEnv, $null, 'Process')
    Remove-Variable password, escaped, content -ErrorAction SilentlyContinue
    if (-not $created) {
        # Échec partiel : on ne laisse pas un keystore sans signing.properties (ou l'inverse).
        foreach ($f in @($Jks, $Props, $FingerprintFile)) { if (Test-Path $f) { Remove-Item -Force $f } }
    }
}

Write-Host ''
Write-Host 'Keystore créé :' -ForegroundColor Green
Write-Host "  $Jks"
Write-Host "  $Props"
Write-Host ''
Write-Host 'Empreinte SHA-256 du certificat de signature (à conserver, publique) :' -ForegroundColor Green
Write-Host "  $fingerprint"
Write-Host ''
Write-Host '!!! SAUVEGARDE OBLIGATOIRE !!!' -ForegroundColor Red
Write-Host 'Copiez release.jks ET le mot de passe dans au moins deux endroits sûrs et séparés' -ForegroundColor Red
Write-Host '(gestionnaire de mots de passe + clé USB chiffrée rangée hors du PC).' -ForegroundColor Red
Write-Host 'Perte du keystore ou du mot de passe = plus AUCUNE mise à jour possible pour les boîtiers' -ForegroundColor Red
Write-Host 'déjà installés (désinstallation puis réinstallation manuelle sur chaque boîtier).' -ForegroundColor Red
Write-Host 'Ne le mettez jamais dans le dépôt Git, un e-mail ou un dossier partagé.' -ForegroundColor Red
