python -m nuitka ^
  --standalone ^
  --onefile ^
  --assume-yes-for-downloads ^
  --windows-disable-console ^
  --include-data-file=CountryCodes.json=CountryCodes.json ^
  --nofollow-import-to=tkinter ^
  --nofollow-import-to=Tkinter ^
  --nofollow-import-to=_tkinter ^
  --lto=yes ^
  --jobs=4 ^
  --output-filename=SmartTGDownloader.exe ^
  audio.py
