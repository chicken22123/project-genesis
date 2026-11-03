# Puts an "Obsidian Client" shortcut on the desktop and in the Start menu, and
# removes the old "Blueprint Client" ones. Run it through make-shortcut.cmd.
#
# By default the shortcut runs app.py from this folder with pythonw, so it
# always opens the code you last pulled, with no console window behind it.
# Pass -UseExe to point it at the built dist\Obsidian Client\Obsidian Client.exe
# instead (that one only changes when you run build.cmd again).

param([switch]$UseExe)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$name = 'Obsidian Client'
$icon = Join-Path $here 'app_icon.ico'
$appPy = Join-Path $here 'app.py'
$builtExe = Join-Path $here 'dist\Obsidian Client\Obsidian Client.exe'

$shell = New-Object -ComObject WScript.Shell
$desktop = [Environment]::GetFolderPath('Desktop')            # follows OneDrive too
$startMenu = [Environment]::GetFolderPath('Programs')
$publicDesktop = [Environment]::GetFolderPath('CommonDesktopDirectory')

# ---------------------------------------------------------------- the target

function Find-Pythonw {
    $pyw = Get-Command pyw.exe -ErrorAction SilentlyContinue
    if ($pyw) { return $pyw.Source }

    # No py launcher: ask whichever python is on PATH where its pythonw lives.
    # That python can be the Microsoft Store placeholder, which only prints an
    # error - so a failure here means "not found", not a reason to stop.
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($python) {
        try {
            $exe = & $python.Source -c "import sys; print(sys.executable)" 2>$null
        } catch {
            $exe = $null
        }
        if ($exe -and (Test-Path $exe)) {
            $candidate = Join-Path (Split-Path -Parent $exe) 'pythonw.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    return $null
}

if ($UseExe) {
    if (-not (Test-Path $builtExe)) {
        Write-Host "There is no built app yet. Run build.cmd first, or run this without -UseExe."
        exit 1
    }
    $target = $builtExe
    $arguments = ''
    $workDir = Split-Path -Parent $builtExe
    $what = 'the built Obsidian Client.exe'
} else {
    $pythonw = Find-Pythonw
    if (-not $pythonw) {
        if (Test-Path $builtExe) {
            Write-Host "Python was not found, so the shortcut will open the built exe instead."
            $target = $builtExe
            $arguments = ''
            $workDir = Split-Path -Parent $builtExe
            $what = 'the built Obsidian Client.exe'
        } else {
            Write-Host "Python was not found, and there is no built exe either."
            Write-Host "Install Python from python.org (tick 'Add to PATH'), or run build.cmd first."
            exit 1
        }
    } else {
        $target = $pythonw
        $arguments = '"' + $appPy + '"'
        $workDir = $here
        $what = 'app.py in ' + $here
    }
}

# ------------------------------------------------- remove the Blueprint ones

# A shortcut counts as an old Blueprint one if it says so in its name, or if
# it points at one of the files the Blueprint version used.
$oldMarkers = @(
    'blueprint_icon',
    'launch-blueprint-client',
    'Blueprint Client.exe',
    '\blueprint-client\launch_app.bat'
)

function Test-OldShortcut($file) {
    if ($file.BaseName -match 'blueprint') { return $true }
    try {
        $link = $shell.CreateShortcut($file.FullName)
        $details = @($link.TargetPath, $link.Arguments, $link.IconLocation) -join ' '
    } catch {
        return $false
    }
    foreach ($marker in $oldMarkers) {
        if ($details.IndexOf($marker, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    }
    return $false
}

$removed = @()
foreach ($folder in @($desktop, $publicDesktop, $startMenu)) {
    if (-not $folder -or -not (Test-Path $folder)) { continue }
    $links = Get-ChildItem -Path $folder -Filter '*.lnk' -File -ErrorAction SilentlyContinue
    foreach ($file in $links) {
        if (-not (Test-OldShortcut $file)) { continue }
        try {
            Remove-Item -LiteralPath $file.FullName -Force
            $removed += $file.FullName
        } catch {
            # The shared desktop needs admin rights; say so rather than fail.
            Write-Host ("Could not remove " + $file.FullName + " - delete it by hand.")
        }
    }
}

# ------------------------------------------------------ make the new ones

function New-ObsidianShortcut($folder) {
    $path = Join-Path $folder ($name + '.lnk')
    $link = $shell.CreateShortcut($path)
    $link.TargetPath = $target
    $link.Arguments = $arguments
    $link.WorkingDirectory = $workDir
    $link.IconLocation = $icon + ',0'
    $link.Description = 'Obsidian Client - Minecraft launcher'
    $link.Save()
    return $path
}

$made = @()
$made += New-ObsidianShortcut $desktop
if ($startMenu -and (Test-Path $startMenu)) { $made += New-ObsidianShortcut $startMenu }

Write-Host ''
if ($removed.Count -gt 0) {
    Write-Host 'Removed the old Blueprint shortcuts:'
    foreach ($path in $removed) { Write-Host ('  ' + $path) }
} else {
    Write-Host 'No old Blueprint shortcuts were found.'
}
Write-Host ''
Write-Host 'Made:'
foreach ($path in $made) { Write-Host ('  ' + $path) }
Write-Host ''
Write-Host ('They open ' + $what + '.')
Write-Host 'Search "Obsidian" in the Start menu, or right-click it there to pin it to the taskbar.'
