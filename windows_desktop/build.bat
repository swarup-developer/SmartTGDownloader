@echo off
setlocal enabledelayedexpansion

REM ==========================================================
REM SmartTG Lightweight Build Pipeline
REM 1. Cython-compiles all app modules to native .pyd binaries
REM    (no readable bytecode ships in the exe)
REM 2. Packages a single-file exe with PyInstaller
REM ==========================================================

set ROOT=%~dp0
cd /d "%ROOT%"

REM --- Optional stage skips (local incremental builds / CI unaffected) ---
REM BUILD_SKIP_SYNC=1      skip "uv sync"
REM BUILD_SKIP_CLEAN=1     skip artifact cleanup (resume an interrupted build)
REM BUILD_SKIP_COMPILE=1   skip staging + Cython compile (reuse existing .pyd)
REM BUILD_STOP_AFTER=cython  stop after compiling .pyd files

if "%BUILD_SKIP_CLEAN%"=="1" goto :skip_clean
echo.
echo === Cleaning old artifacts ===
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
del /q *.exe 2>nul
rmdir /s /q build_src 2>nul
:skip_clean

if "%BUILD_SKIP_SYNC%"=="1" goto :skip_sync
echo.
echo === Syncing uv environment ===
REM --group build: Cython/PyInstaller live in the "build" dependency group.
uv sync --group build || goto :error
:skip_sync

if "%BUILD_SKIP_COMPILE%"=="1" goto :skip_compile
echo.
echo === Staging sources for compilation ===
mkdir build_src\smarttgdownloader
copy SmartTGDownloader.py build_src\ >nul
copy smarttgdownloader\*.py build_src\smarttgdownloader\ >nul
if not exist build_src\smarttgdownloader\__init__.py goto :error

echo.
echo === Creating setup.py for Cython compilation ===
> build_src\setup.py (
    echo import os
    echo from setuptools import setup
    echo from Cython.Build import cythonize
    echo.
    echo modules = [
    echo     "smarttgdownloader/utils.py",
    echo     "smarttgdownloader/worker.py",
    echo     "smarttgdownloader/ui.py",
    echo ]
    echo if os.path.exists^("smarttgdownloader/_secrets.py"^):
    echo     modules.append^("smarttgdownloader/_secrets.py"^)
    echo.
    echo setup^(
    echo     name="smarttgdownloader",
    echo     ext_modules=cythonize^(
    echo         modules,
    echo         compiler_directives={
    echo             "language_level": "3",
    echo             "embedsignature": False,
    echo             "binding": False,
    echo         },
    echo         nthreads=0,
    echo     ^),
    echo ^)
)

echo.
echo === Compiling Python sources to native binaries (Cython) ===
pushd build_src
uv run --group build --project .. python setup.py build_ext --inplace || (popd & goto :error)
popd

dir /b build_src\smarttgdownloader\utils*.pyd >nul 2>&1 || goto :error
dir /b build_src\smarttgdownloader\worker*.pyd >nul 2>&1 || goto :error
dir /b build_src\smarttgdownloader\ui*.pyd >nul 2>&1 || goto :error
:skip_compile

if /i "%BUILD_STOP_AFTER%"=="cython" (
    echo Stopping after Cython compile as requested.
    exit /b 0
)

echo.
echo === Assembling package layout for PyInstaller ===
if not exist smarttgdownloader_compiled mkdir smarttgdownloader_compiled
del /q smarttgdownloader_compiled\* 2>nul
copy build_src\smarttgdownloader\__init__.py smarttgdownloader_compiled\ >nul
copy build_src\smarttgdownloader\*.pyd smarttgdownloader_compiled\ >nul

echo.
echo === Running PyInstaller build (onefile, windowed) ===
REM --add-data paths must be absolute: with --specpath they resolve relative
REM to the spec directory, not the current directory.
uv run --group build pyinstaller --onefile --noconsole ^
  --name SmartTGDownloader ^
  --add-data "%ROOT%CountryCodes.json;." ^
  --add-data "%ROOT%nvdaControllerClient64.dll;." ^
  --paths "%ROOT%smarttgdownloader_compiled" ^
  --specpath "%ROOT%build" ^
  --distpath "%ROOT%dist" ^
  --workpath "%ROOT%build\pyinstaller" ^
  "%ROOT%SmartTGDownloader_compiled_entry.py" || goto :error

if not exist dist\SmartTGDownloader.exe goto :error

echo.
echo === Cleaning intermediate sources ===
rmdir /s /q build_src
rmdir /s /q smarttgdownloader_compiled

echo.
echo === Build finished successfully ===
echo Output: dist\SmartTGDownloader.exe
if not defined GITHUB_ACTIONS pause
exit /b 0

:error
echo.
echo !!! BUILD FAILED !!!
if not defined GITHUB_ACTIONS pause
exit /b 1
