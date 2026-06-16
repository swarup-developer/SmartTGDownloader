@echo off
setlocal

REM ===============================
REM SmartTG Hardened Build Pipeline
REM ===============================

set ROOT=%~dp0
cd /d "%ROOT%"

echo.
echo === Cleaning old artifacts ===
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
del /q *.exe 2>nul

echo.
echo === Syncing uv environment ===
uv sync || goto :error

echo.
echo === Running Nuitka build ===

uv run python -m nuitka ^
  --standalone ^
  --onefile ^
  --assume-yes-for-downloads ^
  --windows-disable-console ^
  --enable-plugin=anti-bloat ^
  --follow-imports ^
  --lto=yes ^
  --jobs=%NUMBER_OF_PROCESSORS% ^
  --python-flag=-O ^
  --python-flag=no_docstrings ^
  --nofollow-import-to=tkinter ^
  --nofollow-import-to=Tkinter ^
  --nofollow-import-to=_tkinter ^
  --noinclude-pytest-mode=nofollow ^
  --noinclude-setuptools-mode=nofollow ^
  --include-data-files=CountryCodes.json=CountryCodes.json ^
  --include-data-files=CountryCodes.json=country.json ^
  --output-dir=dist ^
  --output-filename=SmartTGDownloader.exe ^
  SmartTGDownloader.py || goto :error

echo.
echo === Build finished successfully ===
if not defined GITHUB_ACTIONS pause
exit /b

:error
echo.
echo !!! BUILD FAILED !!!
if not defined GITHUB_ACTIONS pause
exit /b 1
