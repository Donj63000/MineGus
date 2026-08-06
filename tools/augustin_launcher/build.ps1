[CmdletBinding()]
param(
    [string]$PythonExecutable = 'C:\Users\nodig\AppData\Local\Programs\Python\Python313\python.exe',
    [switch]$Deploy
)

$ErrorActionPreference = 'Stop'
$launcherRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$temporaryRoot = 'C:\tmp\augustin_launcher_build'
$distDirectory = Join-Path $temporaryRoot 'dist'
$workDirectory = Join-Path $temporaryRoot 'work'
$specDirectory = Join-Path $temporaryRoot 'spec'
$entryPoint = Join-Path $launcherRoot 'augustin_launcher\app.py'

if (-not (Test-Path -LiteralPath $PythonExecutable)) {
    throw "Python est introuvable : $PythonExecutable"
}

# Ici, je lance les tests avant de fabriquer un exécutable livrable.
& $PythonExecutable -m unittest discover -s (Join-Path $launcherRoot 'tests') -t $launcherRoot
if ($LASTEXITCODE -ne 0) {
    throw 'Les tests unitaires du launcher ont échoué.'
}

New-Item -ItemType Directory -Force -Path $distDirectory, $workDirectory, $specDirectory | Out-Null

# Ici, je garde les artefacts PyInstaller hors du dépôt pour ne rien polluer.
& $PythonExecutable -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --windowed `
    --name AUGUSTIN_Launcher `
    --paths $launcherRoot `
    --distpath $distDirectory `
    --workpath $workDirectory `
    --specpath $specDirectory `
    $entryPoint
if ($LASTEXITCODE -ne 0) {
    throw 'La compilation PyInstaller a échoué.'
}

$artifact = Join-Path $distDirectory 'AUGUSTIN_Launcher.exe'
if (-not (Test-Path -LiteralPath $artifact)) {
    throw "L'exécutable attendu est introuvable : $artifact"
}

Write-Host "Exécutable généré : $artifact"

if ($Deploy) {
    $serverDirectory = 'C:\Users\nodig\Desktop\SERVEUR MINECRAFT AUGUSTIN\PaperServer'
    $destination = Join-Path $serverDirectory 'AUGUSTIN_Launcher.exe'
    if (-not (Test-Path -LiteralPath (Join-Path $serverDirectory 'paper.jar'))) {
        throw "paper.jar est introuvable dans : $serverDirectory"
    }
    if (Test-Path -LiteralPath $destination) {
        throw "Le fichier existe déjà et ne sera pas écrasé : $destination"
    }

    # Ici, je dépose un nouveau nom de fichier pour préserver SERVEUR.exe.
    Copy-Item -LiteralPath $artifact -Destination $destination
    Write-Host "Exécutable déployé : $destination"
}
