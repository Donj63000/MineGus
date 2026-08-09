[CmdletBinding()]
param(
    [string]$PythonExecutable = '',
    [switch]$Deploy,
    [string]$ServerDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$launcherRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$temporaryRoot = 'C:\tmp\augustin_launcher_build'
$distDirectory = Join-Path $temporaryRoot 'dist'
$workDirectory = Join-Path $temporaryRoot 'work'
$specDirectory = Join-Path $temporaryRoot 'spec'
$entryPoint = Join-Path $launcherRoot 'augustin_launcher\app.py'
$launcherName = 'AugLauncher'
$launcherExecutableName = "$launcherName.exe"
$legacyLauncherName = 'AUGUSTIN_Launcher'
$legacyLauncherExecutableName = "$legacyLauncherName.exe"

function Resolve-PythonCommand {
    param([string]$ExplicitExecutable)

    if ($ExplicitExecutable) {
        if (-not (Test-Path -LiteralPath $ExplicitExecutable -PathType Leaf)) {
            throw "Python est introuvable : $ExplicitExecutable"
        }
        return [PSCustomObject]@{ Executable = $ExplicitExecutable; PrefixArguments = @() }
    }

    $knownPaths = @()
    if ($env:LOCALAPPDATA) {
        $knownPaths += Join-Path $env:LOCALAPPDATA 'Programs\Python\Python313\python.exe'
        $knownPaths += Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312\python.exe'
        $knownPaths += Join-Path $env:LOCALAPPDATA 'Programs\Python\Python311\python.exe'
    }
    foreach ($candidate in $knownPaths) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [PSCustomObject]@{ Executable = $candidate; PrefixArguments = @() }
        }
    }

    $pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        return [PSCustomObject]@{ Executable = $pythonCommand.Source; PrefixArguments = @() }
    }

    $pyLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($pyLauncher) {
        return [PSCustomObject]@{ Executable = $pyLauncher.Source; PrefixArguments = @('-3') }
    }

    throw 'Python 3 est introuvable. Installe Python puis relance ce script.'
}

$python = Resolve-PythonCommand -ExplicitExecutable $PythonExecutable
$pythonExe = $python.Executable
$pythonPrefixArguments = @($python.PrefixArguments)
Write-Host "Python utilisé : $pythonExe $($pythonPrefixArguments -join ' ')"

& $pythonExe @pythonPrefixArguments -m unittest discover -s (Join-Path $launcherRoot 'tests') -t $launcherRoot
if ($LASTEXITCODE -ne 0) {
    throw "Les tests unitaires du launcher ont échoué. Aucun exécutable n’a été généré."
}

& $pythonExe @pythonPrefixArguments -c 'import PyInstaller'
if ($LASTEXITCODE -ne 0) {
    throw 'PyInstaller est absent. Installe-le avec : python -m pip install --upgrade pyinstaller'
}

Remove-Item -LiteralPath $distDirectory -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $workDirectory -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $specDirectory -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $distDirectory, $workDirectory, $specDirectory | Out-Null

& $pythonExe @pythonPrefixArguments -m PyInstaller `
    --noconfirm `
    --clean `
    --noupx `
    --onefile `
    --windowed `
    --name $launcherName `
    --paths $launcherRoot `
    --distpath $distDirectory `
    --workpath $workDirectory `
    --specpath $specDirectory `
    $entryPoint
if ($LASTEXITCODE -ne 0) {
    throw 'La compilation PyInstaller a échoué.'
}

$artifact = Join-Path $distDirectory $launcherExecutableName
if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
    throw "L’exécutable attendu est introuvable : $artifact"
}

Write-Host "Exécutable généré : $artifact" -ForegroundColor Green

if ($Deploy) {
    if (-not $ServerDirectory) {
        $ServerDirectory = if ($env:AUGUSTIN_SERVER_DIR) {
            $env:AUGUSTIN_SERVER_DIR
        }
        else {
            'C:\Users\nodig\Desktop\SERVEUR MINECRAFT AUGUSTIN\PaperServer'
        }
    }

    if (-not (Test-Path -LiteralPath (Join-Path $ServerDirectory 'paper.jar') -PathType Leaf)) {
        throw "paper.jar est introuvable dans : $ServerDirectory"
    }

    $runningLauncher = Get-Process -Name $launcherName, $legacyLauncherName -ErrorAction SilentlyContinue
    if ($runningLauncher) {
        throw "Ferme $launcherExecutableName et $legacyLauncherExecutableName avant de déployer la nouvelle version."
    }

    $destination = Join-Path $ServerDirectory $launcherExecutableName
    $legacyDestination = Join-Path $ServerDirectory $legacyLauncherExecutableName
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        $backup = Join-Path $ServerDirectory "$launcherName.backup-$timestamp.exe"
        Copy-Item -LiteralPath $destination -Destination $backup
        Write-Host "Sauvegarde de l’ancienne version : $backup"
    }

    # Ici, je vérifie la copie temporaire avant de remplacer l’exécutable utilisé par le serveur.
    $temporaryDestination = Join-Path $ServerDirectory "$launcherName.new.exe"
    try {
        Copy-Item -LiteralPath $artifact -Destination $temporaryDestination -Force
        $artifactHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifact).Hash
        $temporaryHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryDestination).Hash
        if ($artifactHash -ne $temporaryHash) {
            throw 'La vérification SHA-256 de la copie temporaire a échoué.'
        }
        Move-Item -LiteralPath $temporaryDestination -Destination $destination -Force
    }
    finally {
        Remove-Item -LiteralPath $temporaryDestination -Force -ErrorAction SilentlyContinue
    }

    if (Test-Path -LiteralPath $legacyDestination -PathType Leaf) {
        $legacyBackup = Join-Path $ServerDirectory "$legacyLauncherName.backup-$timestamp.exe"
        Move-Item -LiteralPath $legacyDestination -Destination $legacyBackup
        Write-Host "Sauvegarde de l’ancien nom : $legacyBackup"
    }

    Write-Host "Nouvelle version déployée : $destination" -ForegroundColor Green
}
