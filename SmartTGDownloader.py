# corrected_app_no_monitors.py
import wx
import sys
import os
import json
import asyncio
import threading
import shutil
import re
import traceback
import datetime
from pathlib import Path

WIN32_AVAILABLE = False
try:
    from win32event import CreateMutex
    from win32api import CloseHandle, GetLastError
    from winerror import ERROR_ALREADY_EXISTS
    WIN32_AVAILABLE = True
except ImportError:
    pass

try:
    import socks
except ImportError:
    socks = None

from telethon import TelegramClient, events, errors, types
from cryptography.fernet import Fernet

CURRENT_VERSION = "8.7.0"

# --- Global Exception Handler ---
def log_crash(exctype, value, tb):
    now = datetime.datetime.now().strftime("%Y-%m-%d %I:%M %p") 
    log_path = APPDATA_ROOT / "crash_log.txt"
    try:
        with open(log_path, "a", encoding="utf-8") as f:
            f.write("\n" + "="*60 + "\n")
            f.write(f"CRASH REPORT - {now}\n")
            f.write("="*60 + "\n")
            f.write(f"Error Type: {exctype.__name__}\n")
            f.write(f"Error Message: {value}\n")
            traceback.print_exception(exctype, value, tb, file=f)
            f.write("-" * 60 + "\n")
    except Exception as e:
        print(f"FATAL: Could not write to crash log at {log_path}: {e}")

    try:
        app = wx.GetApp()
        if app and app.IsMainLoopRunning():
            msg = ("We apologize, but the application has encountered a serious error.\n\n"
                   f"A crash log has been saved to:\n{log_path}\n\n"
                   "Please send this file to the developer for assistance.")
            wx.MessageBox(msg, "Fatal Error", wx.OK | wx.ICON_ERROR)
    except Exception:
        pass
    
    sys.exit(1)

sys.excepthook = log_crash

# --- Path & Config Setup ---
def get_appdata_root():
    appdata = os.getenv('APPDATA')
    root = Path(appdata) / "NemisTelegramDownloader" / "bot"
    root.mkdir(parents=True, exist_ok=True)
    return root

APPDATA_ROOT = get_appdata_root()
SESSION_FILE = APPDATA_ROOT / "user_session"
CONFIG_FILE_ENC = APPDATA_ROOT / "config.enc"
KEY_FILE = APPDATA_ROOT / "key.key"
SETTINGS_FILE = APPDATA_ROOT / "settings.json"
COUNTRY_FILE = Path("CountryCodes.json")
DOWNLOAD_ROOT = Path.home() / "Downloads" / "TelegramDownloads"
DOWNLOAD_ROOT.mkdir(parents=True, exist_ok=True)

DEVICE_INFO = {
    "device_model": "Accessible PC Client",
    "system_version": "Windows 11",
    "app_version": CURRENT_VERSION,
    "lang_code": "en",
    "system_lang_code": "en-US"
}

# --- Encryption Utils ---
def load_or_create_key():
    if KEY_FILE.exists(): return KEY_FILE.read_bytes()
    key = Fernet.generate_key()
    KEY_FILE.write_bytes(key)
    return key

FERNET_KEY = load_or_create_key()
FERNET = Fernet(FERNET_KEY)

def save_encrypted_config(data: dict):
    raw = json.dumps(data).encode("utf-8")
    token = FERNET.encrypt(raw)
    CONFIG_FILE_ENC.write_bytes(token)

def load_encrypted_config() -> dict:
    if not CONFIG_FILE_ENC.exists(): return {}
    try:
        token = CONFIG_FILE_ENC.read_bytes()
        raw = FERNET.decrypt(token)
        return json.loads(raw.decode("utf-8"))
    except: return {}

# --- Settings Manager ---
def save_settings(data: dict):
    current = load_settings()
    current.update(data)
    SETTINGS_FILE.write_text(json.dumps(current), encoding='utf-8')

def load_settings() -> dict:
    defaults = {
        'proxy_enabled': False, 
        'proxy_type': 'SOCKS5',
        'proxy_host': '127.0.0.1', 
        'proxy_port': 1080
    }
    if not SETTINGS_FILE.exists(): return defaults
    try:
        saved = json.loads(SETTINGS_FILE.read_text(encoding='utf-8'))
        defaults.update(saved)
        return defaults
    except: return defaults

def load_country_codes():
    if not COUNTRY_FILE.exists(): return []
    try:
        with open(COUNTRY_FILE, 'r', encoding='utf-8') as f:
            data = json.load(f)
            return data if isinstance(data, list) else [] 
    except: return []

# --- Helper Function (PURE) ---
def _clean_filename(msg):
    """Pure helper function to extract filename from message object."""
    fname = "Unknown"
    if msg.document:
        for attr in msg.document.attributes:
            if isinstance(attr, types.DocumentAttributeFilename):
                fname = attr.file_name
                break
            if isinstance(attr, types.DocumentAttributeAudio):
                p = attr.performer or ""
                if p.strip().lower() == "unknown": p = ""
                t = attr.title or "Audio"
                ext = ".mp3" if msg.file.mime_type == "audio/mpeg" else ".m4a"
                if p: fname = f"{p} - {t}{ext}"
                else: fname = f"{t}{ext}"
                break
    
    if fname == "Unknown":
        ext = ".unknown"
        if msg.file.mime_type: ext = "." + msg.file.mime_type.split('/')[-1]
        fname = f"File_{msg.id}{ext}"
        
    return fname

# --- Progress Dialog Class ---
class DownloadProgressDialog(wx.Dialog):
    def __init__(self, parent):
        super().__init__(parent, title="Processing Downloads...", size=(500, 350))
        self.parent = parent
        self.stop_requested = False
        
        panel = wx.Panel(self)
        vbox = wx.BoxSizer(wx.VERTICAL)
        
        self.lbl_current = wx.StaticText(panel, label="Initializing...")
        font = self.lbl_current.GetFont()
        font.MakeBold()
        self.lbl_current.SetFont(font)
        vbox.Add(self.lbl_current, 0, wx.ALL|wx.EXPAND, 10)
        
        self.gauge = wx.Gauge(panel, range=100, size=(250, 25))
        vbox.Add(self.gauge, 0, wx.ALL|wx.EXPAND, 10)
        
        vbox.Add(wx.StaticText(panel, label="Details:"), 0, wx.LEFT, 10)
        self.txt_log = wx.TextCtrl(panel, style=wx.TE_MULTILINE|wx.TE_READONLY|wx.HSCROLL)
        vbox.Add(self.txt_log, 1, wx.ALL|wx.EXPAND, 10)
        
        self.btn_stop = wx.Button(panel, label="Stop Download")
        self.btn_stop.Bind(wx.EVT_BUTTON, self.on_stop)
        vbox.Add(self.btn_stop, 0, wx.ALL|wx.CENTER, 10)
        
        panel.SetSizer(vbox)
        self.CenterOnParent()

    def on_stop(self, event):
        self.stop_requested = True
        self.lbl_current.SetLabel("Stopping... Please wait.")
        self.btn_stop.Disable()
        if self.parent.worker:
            self.parent.worker.stop_download()

    def _format_bytes(self, size):
        if size is None:
            return "0 MB"
        power = 1024
        n = 0
        power_labels = {0: '', 1: 'K', 2: 'M', 3: 'G', 4: 'T'}
        while size > power and n < len(power_labels) -1 :
            size /= power
            n += 1
        return f"{size:.2f} {power_labels[n]}B"

    def update_progress(self, filename, percent, current_bytes, total_bytes):
        current_mb = self._format_bytes(current_bytes)
        total_mb = self._format_bytes(total_bytes)
        self.lbl_current.SetLabel(f"Downloading: {filename} ({current_mb} / {total_mb})")
        self.gauge.SetValue(percent)

    def log_message(self, message):
        self.txt_log.AppendText(f"{message}\n")

# --- Telegram Worker ---
class TelegramWorker:
    def __init__(self, update_callback, progress_callback, login_success_callback):
        self.loop = asyncio.new_event_loop()
        self.client = None
        self.update_callback = update_callback 
        self.progress_callback = progress_callback
        self.login_success_callback = login_success_callback
        self.stop_flag = False
        self.semaphore = asyncio.Semaphore(4)
        self.thread = threading.Thread(target=self._start_loop, daemon=True)
        self.thread.start()

    def _start_loop(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def _run_task(self, coro):
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    def update_gui(self, message, is_error=False):
        wx.CallAfter(self.update_callback, message, is_error)
        
    def stop_download(self):
        self.stop_flag = True

    def get_proxy_args(self):
        p = load_settings()
        if not p.get('proxy_enabled', False): return None
        if not socks: return None
        ptype = socks.SOCKS5 if p['proxy_type'] == 'SOCKS5' else socks.HTTP
        return (ptype, p['proxy_host'], int(p['proxy_port']), True, p.get('proxy_user'), p.get('proxy_pass'))
    
    def _get_clean_filename_str(self, msg):
        return _clean_filename(msg)

    async def connect_and_send_code(self, api_id, api_hash, phone):
        try:
            proxy = self.get_proxy_args()
            self.client = TelegramClient(str(SESSION_FILE), api_id, api_hash, proxy=proxy, **DEVICE_INFO)
            await self.client.connect()
            if not await self.client.is_user_authorized():
                await self.client.send_code_request(phone)
                self.update_gui("Code Sent!")
                return "CODE_SENT"
            else:
                self.update_gui("Already Logged In")
                wx.CallAfter(self.login_success_callback)
                return "ALREADY_LOGGED_IN"
        except Exception as e:
            self.update_gui(f"Error: {e}", True)
            return "ERROR"

    async def complete_login(self, phone, code, password=None):
        try:
            if password: await self.client.sign_in(password=password)
            else: await self.client.sign_in(phone, code)
            wx.CallAfter(self.login_success_callback)
            return "SUCCESS"
        except errors.SessionPasswordNeededError: return "2FA_REQUIRED"
        except Exception as e:
            self.update_gui(f"Login Error: {e}", True); return "ERROR"

    async def connect_silent(self, api_id, api_hash):
        try:
            proxy = self.get_proxy_args()
            self.client = TelegramClient(str(SESSION_FILE), api_id, api_hash, proxy=proxy, **DEVICE_INFO)
            await self.client.connect()
            return True
        except: return False

    async def check_for_updates(self):
        await asyncio.sleep(0) 
        return {"update": False}

    async def perform_update(self):
        for i in range(1, 101, 20):
            self.update_gui(f"Downloading Update... {i}%")
            await asyncio.sleep(0.3)
        self.update_gui("Update Complete. Restarting..."); return True

    async def analyze_link(self, link, filter_types):
        self.stop_flag = False

        try:
            wx.CallAfter(self.progress_callback, "START_DIALOG", 0, 0)

            link = link.strip().rstrip('/')
            if "t.me/c/" in link:
                parts = link.split("/c/")[-1].split("/")
                entity_id = int("-100" + parts[0])
                msg_id = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else None
            else:
                parts = link.split("/")
                entity_id = parts[-2] if parts[-1].isdigit() else parts[-1]
                msg_id = int(parts[-1]) if parts[-1].isdigit() else None

            wx.CallAfter(self.progress_callback, "LOG", "Fetching Channel Info...", 0)
            entity = await self.client.get_entity(entity_id)
            title = entity.title

            if msg_id:
                msg = await self.client.get_messages(entity, ids=msg_id)
                await self._download_item(msg, title)
            else:
                await self._bulk_download(entity, title, filter_types)

            wx.CallAfter(self.progress_callback, "FINISHED", 0, 0)

        except Exception as e:
            wx.CallAfter(self.progress_callback, "LOG", f"Error: {e}", 0)
            wx.CallAfter(self.progress_callback, "FINISHED", 0, 0)

    async def _bulk_download(self, entity, title, filters):
        wx.CallAfter(self.progress_callback, "LOG", f"Scanning channel: {title}...", 0)
        
        all_messages = []
        async for msg in self.client.iter_messages(entity):
            if self.stop_flag: break
            if not msg.media: continue

            mime = msg.file.mime_type or ""
            cat = "Docs"
            if mime.startswith("audio"): cat = "Audio"
            elif mime.startswith("video"): cat = "Video"
            elif mime.startswith("image"): cat = "Images"

            if "All" in filters or cat in filters:
                all_messages.append(msg)
        
        if self.stop_flag: return
        
        wx.CallAfter(self.progress_callback, "LOG", f"Found {len(all_messages)} files. Sorting...", 0)

        def natural_sort_key(msg):
            fname = self._get_clean_filename_str(msg)
            return [int(text) if text.isdigit() else text.lower()
                    for text in re.split('([0-9]+)', fname)]

        all_messages.sort(key=natural_sort_key)

        tasks = [self._download_item(msg, title) for msg in all_messages]
        await asyncio.gather(*tasks)
        
    async def _download_item(self, msg, title):
        async with self.semaphore:
            if self.stop_flag:
                return

            clean_name = self._get_clean_filename_str(msg)
            clean_chan = re.sub(r'[<>:"/\\|?*]', '', title).strip()
            
            mime = msg.file.mime_type or ""
            subfolder = "Docs"
            if mime.startswith("audio"): subfolder = "Audio"
            elif mime.startswith("video"): subfolder = "Video"
            elif mime.startswith("image"): subfolder = "Images"
            
            folder = DOWNLOAD_ROOT / clean_chan / subfolder
            folder.mkdir(parents=True, exist_ok=True)
            path = folder / clean_name
            
            total_size = msg.file.size
            download_offset = 0
            
            if path.exists():
                local_size = path.stat().st_size
                if local_size == total_size:
                    wx.CallAfter(self.progress_callback, "LOG", f"Skipping (Exists): {clean_name}", 0)
                    return True 
                elif local_size < total_size:
                    download_offset = local_size
                    wx.CallAfter(self.progress_callback, "LOG", f"Resuming: {clean_name} ({local_size} bytes)", 0)
                else:
                    path.unlink()
            
            wx.CallAfter(self.progress_callback, "LOG", f"Downloading: {clean_name}", 0)

            try:
                with open(path, 'ab') as f:
                    async for chunk in self.client.iter_download(msg.media, offset=download_offset):
                        if self.stop_flag: 
                            wx.CallAfter(self.progress_callback, "LOG", "Stopped by User.", 0)
                            raise asyncio.CancelledError("Download stopped by user")
                        f.write(chunk)
                        download_offset += len(chunk)
                        
                        percent = int((download_offset / total_size) * 100) if total_size > 0 else 0
                        wx.CallAfter(self.progress_callback, "PROGRESS", (clean_name, percent, download_offset, total_size), 0)

                wx.CallAfter(self.progress_callback, "LOG", f"Completed: {clean_name}", 0)
                return True
            except asyncio.CancelledError:
                wx.CallAfter(self.progress_callback, "LOG", f"Cancelled: {clean_name}", 0)
                return False
            except Exception as e:
                wx.CallAfter(self.progress_callback, "LOG", f"Error downloading {clean_name}: {e}", 0)
                return False

# --- GUI Classes ---

class HelpListDialog(wx.Dialog):
    def __init__(self, parent):
        super().__init__(parent, title="Help - Smart TG Downloader", size=(650, 500))
        p = wx.Panel(self)
        v = wx.BoxSizer(wx.VERTICAL)
        
        help_text = """Welcome to Smart TG Downloader
Thank you for using Smart TG Downloader. This help guide explains how the application works.

What does this app do?
Smart TG Downloader lets you download Telegram channel content using a single link. Instead of downloading files one by one, the app automatically collects and saves all supported media for you in the correct order.

Getting started (first-time setup)
To use the app, you need a Telegram API login. This is a one-time setup.
1. Open my.telegram.org
2. Select API Development
3. Create an app to receive your API ID and API Hash
4. Open Smart TG Downloader
5. Select your country, enter your phone number, and verify using the OTP sent by Telegram.

How to download content
1. Copy a Telegram channel or message link (e.g., t.me/channelname)
2. Paste the link into the app
3. Click Process Link

Key features:
- Bulk Download: Download entire channels with one click.
- Natural Sorting: Files are ordered logically (Part 1, Part 2, etc).
- Resume support: If internet drops, downloads resume from where they left off.
- Duplicate protection: Files already downloaded are skipped."""

        self.txt_help = wx.TextCtrl(p, value=help_text, style=wx.TE_MULTILINE|wx.TE_READONLY)
        v.Add(self.txt_help, 1, wx.EXPAND|wx.ALL, 10)
        v.Add(wx.Button(p, wx.ID_OK, "Close"), 0, wx.CENTER|wx.BOTTOM, 10)
        p.SetSizer(v)
        self.txt_help.SetFocus()

class LoginPanel(wx.Panel):
    def __init__(self, parent, mainframe):
        super().__init__(parent)
        self.mainframe = mainframe
        self.data = {}
        self.selected_country_code = ""
        self.country_map = {}     
        self.main_sizer = wx.BoxSizer(wx.VERTICAL)
        self.status = wx.StaticText(self, label="TG Downloader Login")
        font = self.status.GetFont(); font.Weight = wx.FONTWEIGHT_BOLD; font.PointSize += 2
        self.status.SetFont(font)
        self.main_sizer.Add(self.status, 0, wx.ALL|wx.CENTER, 20)
        self.content_panel = wx.Panel(self)
        self.content_sizer = wx.BoxSizer(wx.VERTICAL)
        self.content_panel.SetSizer(self.content_sizer)
        self.main_sizer.Add(self.content_panel, 1, wx.EXPAND|wx.ALL, 10)
        lbl_help = wx.StaticText(self, label="Need help? Press F1.")
        self.main_sizer.Add(lbl_help, 0, wx.ALL|wx.CENTER, 10)
        self.SetSizer(self.main_sizer)
        self.show_start_screen()
        
    def clear_content(self): self.content_sizer.Clear(True)

    def show_start_screen(self):
        self.clear_content()
        self.status.SetLabel("Welcome")
        btn = wx.Button(self.content_panel, label="Start Setup")
        btn.Bind(wx.EVT_BUTTON, lambda e: self.show_step_api_id())
        self.content_sizer.Add(btn, 0, wx.CENTER|wx.TOP, 40)
        self.content_panel.Layout(); btn.SetFocus()

    def show_step_api_id(self):
        self.clear_content()
        self.status.SetLabel("Step 1: Enter API ID")
        lbl = wx.StaticText(self.content_panel, label="API ID:")
        self.txt_api_id = wx.TextCtrl(self.content_panel, style=wx.TE_PROCESS_ENTER)
        self.txt_api_id.SetName("Enter API ID"); self.txt_api_id.SetHint("Enter API ID")
        self.txt_api_id.Bind(wx.EVT_TEXT_ENTER, self.on_api_id_next)
        btn_next = wx.Button(self.content_panel, label="Next")
        btn_next.Bind(wx.EVT_BUTTON, self.on_api_id_next)
        self.content_sizer.Add(lbl, 0, wx.LEFT|wx.TOP, 10)
        self.content_sizer.Add(self.txt_api_id, 0, wx.EXPAND|wx.ALL, 10)
        self.content_sizer.Add(btn_next, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_api_id.SetFocus()

    def on_api_id_next(self, e):
        val = self.txt_api_id.GetValue()
        if not val.isdigit(): wx.MessageBox("API ID must be a number.", "Error"); return
        self.data['api_id'] = int(val)
        self.show_step_api_hash()

    def show_step_api_hash(self):
        self.clear_content()
        self.status.SetLabel("Step 2: Enter API Hash")
        lbl = wx.StaticText(self.content_panel, label="API Hash:")
        self.txt_hash = wx.TextCtrl(self.content_panel, style=wx.TE_PROCESS_ENTER)
        self.txt_hash.SetName("Enter API Hash"); self.txt_hash.SetHint("Enter API Hash")
        self.txt_hash.Bind(wx.EVT_TEXT_ENTER, self.on_hash_next)
        btn_next = wx.Button(self.content_panel, label="Next")
        btn_next.Bind(wx.EVT_BUTTON, self.on_hash_next)
        self.content_sizer.Add(lbl, 0, wx.LEFT|wx.TOP, 10)
        self.content_sizer.Add(self.txt_hash, 0, wx.EXPAND|wx.ALL, 10)
        self.content_sizer.Add(btn_next, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_hash.SetFocus()

    def on_hash_next(self, e):
        val = self.txt_hash.GetValue().strip()
        if not val: return
        self.data['api_hash'] = val
        self.show_step_country()

    def show_step_country(self):
        self.clear_content()
        self.status.SetLabel("Step 3: Select Country")
        lbl = wx.StaticText(self.content_panel, label="Please select your country:")
        
        country_data = load_country_codes()
        if not country_data:
            wx.MessageBox("CountryCodes.json file missing!", "Error"); return

        self.display_choices = []
        self.country_map = {}
        country_data.sort(key=lambda x: x.get('name', ''))

        for item in country_data:
            name = item.get('name', 'Unknown')
            code = item.get('dial_code', '')
            display_str = f"{name} ({code})"
            self.display_choices.append(display_str)
            self.country_map[display_str] = code
        
        self.lst_country = wx.ListBox(self.content_panel, choices=self.display_choices)
        self.lst_country.SetName("List of countries") 
        btn_next = wx.Button(self.content_panel, label="Next")
        btn_next.Bind(wx.EVT_BUTTON, self.on_country_next)
        self.content_sizer.Add(lbl, 0, wx.LEFT|wx.TOP, 10)
        self.content_sizer.Add(self.lst_country, 1, wx.EXPAND|wx.ALL, 10)
        self.content_sizer.Add(btn_next, 0, wx.ALIGN_RIGHT|wx.RIGHT|wx.BOTTOM, 10)
        self.content_panel.Layout(); self.lst_country.SetFocus()

    def on_country_next(self, e):
        sel = self.lst_country.GetStringSelection()
        if not sel: wx.MessageBox("Please select a country.", "Info"); return
        self.selected_country_code = self.country_map[sel]
        self.show_step_phone()

    def show_step_phone(self):
        wx.MessageBox("Please enter your mobile number in the next screen.", "Info")
        self.clear_content()
        self.status.SetLabel("Step 4: Enter Mobile Number")
        hbox = wx.BoxSizer(wx.HORIZONTAL)
        txt_code = wx.TextCtrl(self.content_panel, value=self.selected_country_code, size=(60, -1), style=wx.TE_READONLY)
        self.txt_phone = wx.TextCtrl(self.content_panel, style=wx.TE_PROCESS_ENTER)
        self.txt_phone.SetName("Enter mobile number"); self.txt_phone.SetHint("Enter Mobile Number")
        self.txt_phone.SetToolTip("Without country code")
        self.txt_phone.Bind(wx.EVT_TEXT_ENTER, self.on_phone_next)
        hbox.Add(txt_code, 0, wx.RIGHT, 5)
        hbox.Add(self.txt_phone, 1, wx.EXPAND)
        btn_next = wx.Button(self.content_panel, label="Next")
        btn_next.Bind(wx.EVT_BUTTON, self.on_phone_next)
        self.content_sizer.Add(wx.StaticText(self.content_panel, label="Number:"), 0, wx.LEFT|wx.TOP, 10)
        self.content_sizer.Add(hbox, 0, wx.EXPAND|wx.ALL, 10)
        self.content_sizer.Add(btn_next, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_phone.SetFocus()

    def on_phone_next(self, e):
        num = self.txt_phone.GetValue().strip()
        if not num: return
        self.data['phone'] = self.selected_country_code + num
        save_encrypted_config({'api_id': self.data['api_id'], 'api_hash': self.data['api_hash']})
        
        self.wait_dialog = wx.ProgressDialog("Connecting", "Please wait, connecting to Telegram...", parent=self, style=wx.PD_APP_MODAL | wx.PD_AUTO_HIDE)
        self.wait_dialog.Pulse()
        self.status.SetLabel("Connecting...")
        self.content_panel.Disable() 
        self.mainframe.worker._run_task(self.async_send_code())

    async def async_send_code(self):
        res = await self.mainframe.worker.connect_and_send_code(
            self.data['api_id'], self.data['api_hash'], self.data['phone']
        )
        wx.CallAfter(self.wait_dialog.Destroy)
        if res == "CODE_SENT": wx.CallAfter(self.show_step_code)
        elif res == "ERROR": wx.CallAfter(self.content_panel.Enable)

    def show_step_code(self):
        wx.MessageBox("Please enter the verification code received on Telegram.", "Info")
        self.content_panel.Enable()
        self.clear_content()
        self.status.SetLabel("Step 5: Enter Code")
        self.txt_code = wx.TextCtrl(self.content_panel, style=wx.TE_PROCESS_ENTER)
        self.txt_code.SetName("Enter verification code"); self.txt_code.SetHint("Enter OTP Code")
        self.txt_code.Bind(wx.EVT_TEXT_ENTER, self.on_code_submit)
        btn_submit = wx.Button(self.content_panel, label="Submit")
        btn_submit.Bind(wx.EVT_BUTTON, self.on_code_submit)
        self.content_sizer.Add(wx.StaticText(self.content_panel, label="Code:"), 0, wx.LEFT|wx.TOP, 10)
        self.content_sizer.Add(self.txt_code, 0, wx.EXPAND|wx.ALL, 10)
        self.content_sizer.Add(btn_submit, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_code.SetFocus()

    def on_code_submit(self, e):
        code = self.txt_code.GetValue().strip()
        if not code: return
        self.status.SetLabel("Verifying...")
        self.content_panel.Disable()
        self.mainframe.worker._run_task(self.async_verify_code(code))

    async def async_verify_code(self, code):
        res = await self.mainframe.worker.complete_login(self.data['phone'], code)
        if res == "2FA_REQUIRED": wx.CallAfter(self.show_step_password)
        elif res == "ERROR": wx.CallAfter(self.content_panel.Enable)

    def show_step_password(self):
        wx.MessageBox("Enter your Cloud Password.", "Info")
        self.content_panel.Enable()
        self.clear_content()
        self.status.SetLabel("Step 6: 2FA Password")
        self.txt_pass = wx.TextCtrl(self.content_panel, style=wx.TE_PASSWORD|wx.TE_PROCESS_ENTER)
        self.txt_pass.SetName("Enter Cloud Password"); self.txt_pass.SetHint("Password")
        self.txt_pass.Bind(wx.EVT_TEXT_ENTER, self.on_pass_submit)
        btn_submit = wx.Button(self.content_panel, label="Submit")
        btn_submit.Bind(wx.EVT_BUTTON, self.on_pass_submit)
        self.content_sizer.Add(wx.StaticText(self.content_panel, label="Password:"), 0, wx.LEFT|wx.TOP, 10)
        self.content_sizer.Add(self.txt_pass, 0, wx.EXPAND|wx.ALL, 10)
        self.content_sizer.Add(btn_submit, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_pass.SetFocus()

    def on_pass_submit(self, e):
        pw = self.txt_pass.GetValue()
        self.status.SetLabel("Verifying Password...")
        self.content_panel.Disable()
        self.mainframe.worker._run_task(self.async_verify_pass(pw))

    async def async_verify_pass(self, pw):
        res = await self.mainframe.worker.complete_login(self.data['phone'], None, password=pw)
        if res == "ERROR": wx.CallAfter(self.content_panel.Enable)

# --- Settings Dialog (Restored: Proxy Only) ---
class SettingsDialog(wx.Dialog):
    def __init__(self, parent, worker):
        super().__init__(parent, title="Configuration", size=(400, 280))
        self.worker = worker
        self.cfg = load_settings()
        p = wx.Panel(self)
        v = wx.BoxSizer(wx.VERTICAL)
        
        # Only Proxy Settings - "Check Update" removed
        sb = wx.StaticBox(p, label="Proxy Configuration (SOCKS5/HTTP)")
        sbs = wx.StaticBoxSizer(sb, wx.VERTICAL)
        
        self.chk_proxy = wx.CheckBox(p, label="Enable Proxy Connection")
        self.chk_proxy.SetValue(self.cfg.get('proxy_enabled', False))
        sbs.Add(self.chk_proxy, 0, wx.ALL, 10)
        
        grid = wx.FlexGridSizer(2, 2, 5, 10)
        grid.Add(wx.StaticText(p, label="Host:"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.txt_host = wx.TextCtrl(p, value=self.cfg.get('proxy_host', '127.0.0.1'))
        grid.Add(self.txt_host, 0, wx.EXPAND)
        
        grid.Add(wx.StaticText(p, label="Port:"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.txt_port = wx.TextCtrl(p, value=str(self.cfg.get('proxy_port', '1080')))
        grid.Add(self.txt_port, 0, wx.EXPAND)
        
        grid.AddGrowableCol(1, 1)
        sbs.Add(grid, 0, wx.EXPAND|wx.ALL, 10)
        
        v.Add(sbs, 0, wx.ALL|wx.EXPAND, 15)
        
        b_save = wx.Button(p, wx.ID_OK, label="Save Configuration")
        b_save.Bind(wx.EVT_BUTTON, self.on_save)
        v.Add(b_save, 0, wx.ALL|wx.CENTER, 10)
        p.SetSizer(v)

    def on_save(self, e):
        data = {
            'proxy_enabled': self.chk_proxy.GetValue(),
            'proxy_type': 'SOCKS5', # Defaulting to SOCKS5 for simplicity
            'proxy_host': self.txt_host.GetValue(),
            'proxy_port': int(self.txt_port.GetValue()) if self.txt_port.GetValue().isdigit() else 1080
        }
        save_settings(data)
        self.EndModal(wx.ID_OK)

class UpdateAvailableDialog(wx.Dialog):
    def __init__(self, parent, version):
        super().__init__(parent, title="Update", size=(300, 150))
        p = wx.Panel(self)
        v = wx.BoxSizer(wx.VERTICAL)
        v.Add(wx.StaticText(p, label=f"New Version {version} Available!"), 0, wx.ALL|wx.CENTER, 20)
        h = wx.BoxSizer(wx.HORIZONTAL)
        b1 = wx.Button(p, label="Update Now")
        b1.Bind(wx.EVT_BUTTON, lambda e: self.EndModal(wx.ID_YES))
        b2 = wx.Button(p, wx.ID_CANCEL, label="Cancel")
        h.Add(b1, 0, wx.ALL, 5); h.Add(b2, 0, wx.ALL, 5)
        v.Add(h, 0, wx.CENTER, 10)
        p.SetSizer(v)

class MainFrame(wx.Frame):
    def __init__(self):
        super().__init__(None, title=f"Smart TG Downloader v{CURRENT_VERSION}", size=(700, 550))
        if sys.platform == 'win32' and WIN32_AVAILABLE:
            self.mutex = CreateMutex(None, False, "Global\\TGDownloader_Mutex_App")
            if GetLastError() == ERROR_ALREADY_EXISTS:
                wx.MessageBox("Application is already running.", "Error", wx.OK | wx.ICON_ERROR)
                self.Destroy(); return

        self.worker = TelegramWorker(self.update_status, self.update_progress, self.on_login_success)
        self.config = load_encrypted_config()
        self.help_shown = False 
        self.progress_dlg = None
        self.is_downloading = False
        
        self.sizer = wx.BoxSizer(wx.VERTICAL)
        self.SetSizer(self.sizer)

        self.login_panel = LoginPanel(self, self)
        self.download_panel = wx.Panel(self)
        self.init_download_ui()

        # --- Menu Setup (FIXED) ---
        self.ID_SETTINGS = wx.NewIdRef()
        
        mb = wx.MenuBar()
        fm = wx.Menu() # File Menu
        hm = wx.Menu() # Help Menu
        
        # Add Settings and Monitor with EXPLICIT IDs
        fm.Append(self.ID_SETTINGS, "&Settings")
        fm.Append(wx.ID_EXIT, "Exit")
        
        hm.Append(wx.ID_HELP, "Help\tF1")
        
        mb.Append(fm, "&File")
        mb.Append(hm, "&Help")
        self.SetMenuBar(mb)
        
        # Bind events using the EXPLICIT IDs (NO wx.ID_ANY)
        self.Bind(wx.EVT_MENU, self.on_settings, id=self.ID_SETTINGS)
        self.Bind(wx.EVT_MENU, self.on_help, id=wx.ID_HELP)
        self.Bind(wx.EVT_MENU, lambda e: self.Close(), id=wx.ID_EXIT)
        self.Bind(wx.EVT_CLOSE, self.on_close)

        if 'api_id' in self.config:
            self.show_download()
            self.update_status("Reconnecting...")
            self.worker._run_task(self.worker.connect_silent(self.config['api_id'], self.config['api_hash']))
        else: self.show_login()

        self.Centre(); self.Show()

    def on_close(self, event):
        if self.is_downloading:
            dlg = wx.MessageDialog(self, "A download is in progress. Are you sure you want to stop and exit?",
                                   "Confirm Exit", wx.YES_NO | wx.ICON_QUESTION)
            result = dlg.ShowModal()
            dlg.Destroy()
            if result == wx.ID_YES:
                self.worker.stop_download()
                self.Destroy()
        else:
            self.Destroy()

    def on_login_success(self):
        self.show_download()
        wx.MessageBox("Logged in successfully.", "Success")

    def show_login(self):
        self.download_panel.Hide(); self.login_panel.Show()
        self.sizer.Clear(); self.sizer.Add(self.login_panel, 1, wx.EXPAND)
        self.Layout()
        if not self.help_shown: self.help_shown = True; wx.CallAfter(self.on_help, None)

    def show_download(self):
        self.login_panel.Hide(); self.download_panel.Show()
        self.sizer.Clear(); self.sizer.Add(self.download_panel, 1, wx.EXPAND)
        self.Layout(); self.txt_link.SetFocus()

    def on_help(self, e): d = HelpListDialog(self); d.ShowModal(); d.Destroy()
    
    def on_settings(self, e): 
        d = SettingsDialog(self, self.worker)
        d.ShowModal()
        d.Destroy()

    def update_status(self, msg, err=False):
        if self.login_panel.IsShown():
            self.login_panel.status.SetLabel(msg)
        else:
            self.lbl_status.SetLabel(msg)

    def update_progress(self, cmd, data, extra=0):
        if cmd == "START_DIALOG":
            self.is_downloading = True
            if not self.progress_dlg:
                self.progress_dlg = DownloadProgressDialog(self)
                self.progress_dlg.Show()
        elif cmd == "FINISHED":
            self.is_downloading = False
            if self.progress_dlg:
                self.progress_dlg.Destroy()
                self.progress_dlg = None
            wx.MessageBox("All tasks completed.", "Finished")
            self.txt_link.SetValue(""); self.txt_link.Show()
        elif cmd == "LOG":
            if self.progress_dlg:
                self.progress_dlg.log_message(data)
        elif cmd == "PROGRESS":
            if self.progress_dlg:
                self.progress_dlg.update_progress(*data)

    def init_download_ui(self):
        p = self.download_panel
        v = wx.BoxSizer(wx.VERTICAL)
        t = wx.StaticText(p, label="TG Downloader"); f=t.GetFont(); f.PointSize+=4; f.Weight=wx.FONTWEIGHT_BOLD; t.SetFont(f)
        v.Add(t, 0, wx.ALL|wx.CENTER, 20)
        self.lbl_instr = wx.StaticText(p, label="Paste your Telegram Link below:")
        v.Add(self.lbl_instr, 0, wx.LEFT|wx.RIGHT|wx.TOP, 25)
        self.txt_link = wx.TextCtrl(p, value="")
        self.txt_link.SetHint("Paste Link Here (e.g. t.me/channel/123)")
        self.txt_link.Bind(wx.EVT_TEXT, self.on_text_change)
        v.Add(self.txt_link, 0, wx.ALL|wx.EXPAND, 20)
        
        hbox = wx.BoxSizer(wx.HORIZONTAL)
        self.btn_process = wx.Button(p, label="Process Link")
        self.btn_process.Bind(wx.EVT_BUTTON, self.on_process)
        self.btn_process.Disable() 
        hbox.Add(self.btn_process, 1, wx.CENTER|wx.EXPAND, 0)
        
        v.Add(hbox, 0, wx.ALL|wx.EXPAND, 20)

        self.btn_open_folder = wx.Button(p, label="Open Download Folder")
        self.btn_open_folder.Bind(wx.EVT_BUTTON, self.on_open_download_folder)
        v.Add(self.btn_open_folder, 0, wx.ALL|wx.EXPAND, 20)

        self.lbl_status = wx.StaticText(p, label="Ready to start.")
        v.Add(self.lbl_status, 1, wx.ALL|wx.CENTER, 20)
        v.Add(wx.StaticText(p, label="Need help? Press F1."), 0, wx.ALL|wx.CENTER, 15)
        p.SetSizer(v); p.Hide()

    def on_open_download_folder(self, event):
        try:
            os.startfile(DOWNLOAD_ROOT)
        except Exception as e:
            wx.MessageBox(f"Could not open download folder: {e}", "Error", wx.OK | wx.ICON_ERROR)

    def on_text_change(self, e):
        enabled = bool(self.txt_link.GetValue().strip())
        self.btn_process.Enable(enabled)

    def on_process(self, e):
        link = self.txt_link.GetValue()
        if not link: return
        parts = link.strip().rstrip('/').split('/')
        if parts[-1].isdigit():
            self.worker._run_task(self.worker.analyze_link(link, ["All"]))
        else:
            d = wx.SingleChoiceDialog(self, "Filter:", "Download", ["All", "Audio", "Video"])
            if d.ShowModal() == wx.ID_OK:
                self.worker._run_task(self.worker.analyze_link(link, [d.GetStringSelection()]))
            d.Destroy()

    async def check_update_task(self):
        res = await self.worker.check_for_updates()
        if res.get('update'):
            wx.CallAfter(self.show_update_prompt, res)

    def show_update_prompt(self, res):
        d = UpdateAvailableDialog(self, res['version'])
        if d.ShowModal() == wx.ID_YES:
            self.worker._run_task(self.worker.perform_update())
        d.Destroy()

if __name__ == "__main__":
    app = wx.App()
    MainFrame()
    app.MainLoop()
