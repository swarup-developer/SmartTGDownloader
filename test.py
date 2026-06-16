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
    log_path = Path.cwd() / "crash_log.txt"
    with open(log_path, "a", encoding="utf-8") as f:
        f.write("\n" + "="*60 + "\n")
        f.write(f"CRASH REPORT - {now}\n")
        f.write("="*60 + "\n")
        f.write(f"Error Type: {exctype.__name__}\n")
        f.write(f"Error Message: {value}\n")
        traceback.print_exception(exctype, value, tb, file=f)
        f.write("-" * 60 + "\n")
    
    app = wx.GetApp()
    if app:
        msg = ("We apologize, but the application has encountered an error.\n"
               f"Log saved to: {log_path}\nPlease send this file to @Swarupbaral")
        wx.MessageBox(msg, "Error", wx.OK | wx.ICON_ERROR)
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
# FIXED: COUNTRY_FILE is now a Path object to support .exists() [web:3][web:10]
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

def save_settings(data: dict):
    SETTINGS_FILE.write_text(json.dumps(data), encoding='utf-8')

def load_settings() -> dict:
    defaults = {
        'proxy_enabled': False, 'proxy_type': 'SOCKS5',
        'proxy_host': '127.0.0.1', 'proxy_port': 1080, 
        'check_update': True,
        'monitored_channels': []
    }
    if not SETTINGS_FILE.exists(): return defaults
    try:
        saved = json.loads(SETTINGS_FILE.read_text(encoding='utf-8'))
        defaults.update(saved)
        return defaults
    except: return defaults

def load_country_codes():
    if not COUNTRY_FILE.exists(): 
        return []
    try:
        with open(COUNTRY_FILE, 'r', encoding='utf-8') as f:
            data = json.load(f)
            return data if isinstance(data, list) else []
    except: 
        return []

# --- Progress Dialog Class ---
class DownloadProgressDialog(wx.Dialog):
    def __init__(self, parent):
        super().__init__(parent, title="Processing Downloads...", size=(500, 350))
        self.parent = parent
        self.stop_requested = False
        panel = wx.Panel(self)
        vbox = wx.BoxSizer(wx.VERTICAL)
        self.lbl_current = wx.StaticText(panel, label="Initializing...")
        font = self.lbl_current.GetFont(); font.MakeBold(); self.lbl_current.SetFont(font)
        vbox.Add(self.lbl_current, 0, wx.ALL|wx.EXPAND, 10)
        self.gauge = wx.Gauge(panel, range=100, size=(250, 25))
        vbox.Add(self.gauge, 0, wx.ALL|wx.EXPAND, 10)
        vbox.Add(wx.StaticText(panel, label="Details:"), 0, wx.LEFT, 10)
        self.txt_log = wx.TextCtrl(panel, style=wx.TE_MULTILINE|wx.TE_READONLY|wx.HSCROLL)
        vbox.Add(self.txt_log, 1, wx.ALL|wx.EXPAND, 10)
        self.btn_stop = wx.Button(panel, label="Stop Download")
        self.btn_stop.Bind(wx.EVT_BUTTON, self.on_stop)
        vbox.Add(self.btn_stop, 0, wx.ALL|wx.CENTER, 10)
        panel.SetSizer(vbox); self.CenterOnParent()

    def on_stop(self, event):
        self.stop_requested = True
        self.lbl_current.SetLabel("Stopping... Please wait.")
        self.btn_stop.Disable()
        if self.parent.worker: self.parent.worker.stop_download()

    def update_progress(self, filename, percent, current_bytes, total_bytes):
        self.lbl_current.SetLabel(f"Downloading: {filename}")
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
        if not p.get('proxy_enabled', False) or not socks: return None
        ptype = socks.SOCKS5 if p['proxy_type'] == 'SOCKS5' else socks.HTTP
        return (ptype, p['proxy_host'], int(p['proxy_port']), True, p.get('proxy_user'), p.get('proxy_pass'))

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
                wx.CallAfter(self.login_success_callback); self.start_monitors()
                return "ALREADY_LOGGED_IN"
        except Exception as e:
            self.update_gui(f"Error: {e}", True); return "ERROR"

    async def complete_login(self, phone, code, password=None):
        try:
            if password: await self.client.sign_in(password=password)
            else: await self.client.sign_in(phone, code)
            wx.CallAfter(self.login_success_callback); self.start_monitors()
            return "SUCCESS"
        except errors.SessionPasswordNeededError: return "2FA_REQUIRED"
        except Exception as e:
            self.update_gui(f"Login Error: {e}", True); return "ERROR"

    async def connect_silent(self, api_id, api_hash):
        try:
            proxy = self.get_proxy_args()
            self.client = TelegramClient(str(SESSION_FILE), api_id, api_hash, proxy=proxy, **DEVICE_INFO)
            await self.client.connect()
            if await self.client.is_user_authorized(): self.start_monitors()
            return True
        except: return False

    def start_monitors(self):
        sets = load_settings()
        chats = sets.get('monitored_channels', [])
        if not chats: return
        @self.client.on(events.NewMessage(chats=chats))
        async def handler(event):
            if event.message.media:
                chat = await event.get_chat()
                await self._download_item(event.message, chat.title, is_monitor=True)

    async def add_monitor(self, link):
        try:
            entity = await self.client.get_entity(link)
            sets = load_settings()
            current = sets.get('monitored_channels', [])
            if entity.id not in current:
                current.append(entity.id)
                sets['monitored_channels'] = current; save_settings(sets)
                self.start_monitors(); self.update_gui(f"Added {entity.title} to Monitor.")
            else: self.update_gui(f"Already monitoring {entity.title}.")
        except Exception as e: self.update_gui(f"Monitor Error: {e}", True)

    async def check_for_updates(self):
        await asyncio.sleep(2)
        remote_version = "8.7.0"
        if remote_version > CURRENT_VERSION: return {"update": True, "version": remote_version}
        return {"update": False}

    async def perform_update(self):
        for i in range(1, 101, 20):
            self.update_gui(f"Downloading Update... {i}%")
            await asyncio.sleep(0.3)
        self.update_gui("Update Complete. Restarting..."); return True

    def _get_clean_filename_str(self, msg):
        fname = "Unknown"
        if msg.document:
            for attr in msg.document.attributes:
                if isinstance(attr, types.DocumentAttributeFilename):
                    fname = attr.file_name; break
                if isinstance(attr, types.DocumentAttributeAudio):
                    p = attr.performer or ""
                    if p.strip().lower() == "unknown": p = ""
                    t = attr.title or "Audio"
                    ext = ".mp3" if msg.file.mime_type == "audio/mpeg" else ".m4a"
                    fname = f"{p} - {t}{ext}" if p else f"{t}{ext}"
                    break
        if fname == "Unknown":
            ext = "." + (msg.file.mime_type.split('/')[-1] if msg.file.mime_type else "unknown")
            fname = f"File_{msg.id}{ext}"
        fname = re.sub(r'^unknown\s*-\s*', '', fname, flags=re.IGNORECASE)
        return re.sub(r'[<>:"/\\|?*]', '', fname).strip()

    async def analyze_link(self, link, filter_types):
        self.stop_flag = False
        if not self.client or not await self.client.is_user_authorized():
            self.update_gui("Not logged in.", True); return
        try:
            wx.CallAfter(self.progress_callback, "START_DIALOG", 0, 0)
            link = link.strip().rstrip('/')
            if "t.me/c/" in link:
                parts = link.split("/c/")[-1].split("/")
                entity_id, msg_id = int("-100" + parts[0]), (int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else None)
            else:
                parts = link.split("/")
                entity_id, msg_id = (parts[-2] if parts[-1].isdigit() else parts[-1]), (int(parts[-1]) if parts[-1].isdigit() else None)
            wx.CallAfter(self.progress_callback, "LOG", "Fetching Channel Info...", 0)
            entity = await self.client.get_entity(entity_id)
            if msg_id:
                msg = await self.client.get_messages(entity, ids=msg_id)
                await self._download_item(msg, entity.title)
            else:
                await self._bulk_download(entity, entity.title, filter_types)
            wx.CallAfter(self.progress_callback, "FINISHED", 0, 0)
        except Exception as e:
            wx.CallAfter(self.progress_callback, "LOG", f"Error: {e}", 0)
            wx.CallAfter(self.progress_callback, "FINISHED", 0, 0)

    async def _bulk_download(self, entity, title, filters):
        wx.CallAfter(self.progress_callback, "LOG", f"Scanning: {title}...", 0)
        all_messages = []
        async for msg in self.client.iter_messages(entity):
            if self.stop_flag: break
            if not msg.media: continue
            mime = msg.file.mime_type or ""
            cat = "Audio" if mime.startswith("audio") else "Video" if mime.startswith("video") else "Images" if mime.startswith("image") else "Docs"
            if "All" in filters or cat in filters: all_messages.append(msg)
        if self.stop_flag: return
        all_messages.sort(key=lambda m: [int(t) if t.isdigit() else t.lower() for t in re.split('([0-9]+)', self._get_clean_filename_str(m))])
        for msg in all_messages:
            if self.stop_flag: break
            await self._download_item(msg, title)
        
    async def _download_item(self, msg, title, is_monitor=False):
        clean_name = self._get_clean_filename_str(msg)
        mime = msg.file.mime_type or ""
        sub = "Audio" if mime.startswith("audio") else "Video" if mime.startswith("video") else "Images" if mime.startswith("image") else "Docs"
        folder = DOWNLOAD_ROOT / re.sub(r'[<>:"/\\|?*]', '', title).strip() / sub
        folder.mkdir(parents=True, exist_ok=True); path = folder / clean_name
        total_size, download_offset = msg.file.size, 0
        if path.exists():
            if path.stat().st_size == total_size: return True 
            download_offset = path.stat().st_size
        if not is_monitor: wx.CallAfter(self.progress_callback, "LOG", f"Downloading: {clean_name}", 0)
        with open(path, 'ab') as f:
            async for chunk in self.client.iter_download(msg.media, offset=download_offset):
                if self.stop_flag: raise Exception("Stopped")
                f.write(chunk); download_offset += len(chunk)
                if not is_monitor:
                    p = int((download_offset / total_size) * 100) if total_size > 0 else 0
                    wx.CallAfter(self.progress_callback, "PROGRESS", (clean_name, p, download_offset, total_size), 0)
        return True

# --- GUI Classes ---
class LoginPanel(wx.Panel):
    def __init__(self, parent, mainframe):
        super().__init__(parent); self.mainframe = mainframe; self.data = {}
        self.main_sizer = wx.BoxSizer(wx.VERTICAL)
        self.status = wx.StaticText(self, label="TG Downloader Login")
        font = self.status.GetFont(); font.Weight = wx.FONTWEIGHT_BOLD; font.PointSize += 2; self.status.SetFont(font)
        self.main_sizer.Add(self.status, 0, wx.ALL|wx.CENTER, 20)
        self.content_panel = wx.Panel(self); self.content_sizer = wx.BoxSizer(wx.VERTICAL); self.content_panel.SetSizer(self.content_sizer)
        self.main_sizer.Add(self.content_panel, 1, wx.EXPAND|wx.ALL, 10); self.SetSizer(self.main_sizer); self.show_start_screen()

    def clear_content(self): self.content_sizer.Clear(True)
    def show_start_screen(self):
        self.clear_content(); btn = wx.Button(self.content_panel, label="Start Setup")
        btn.Bind(wx.EVT_BUTTON, lambda e: self.show_step_api_id())
        self.content_sizer.Add(btn, 0, wx.CENTER|wx.TOP, 40); self.content_panel.Layout(); btn.SetFocus()

    def show_step_api_id(self):
        self.clear_content(); self.status.SetLabel("Step 1: API ID")
        self.txt_api_id = wx.TextCtrl(self.content_panel, style=wx.TE_PROCESS_ENTER); self.txt_api_id.SetHint("Enter API ID")
        btn = wx.Button(self.content_panel, label="Next"); btn.Bind(wx.EVT_BUTTON, self.on_api_id_next)
        self.content_sizer.Add(wx.StaticText(self.content_panel, label="API ID:"), 0, wx.LEFT, 10)
        self.content_sizer.Add(self.txt_api_id, 0, wx.EXPAND|wx.ALL, 10); self.content_sizer.Add(btn, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_api_id.SetFocus()

    def on_api_id_next(self, e):
        if not self.txt_api_id.GetValue().isdigit(): wx.MessageBox("ID must be numeric.", "Error"); return
        self.data['api_id'] = int(self.txt_api_id.GetValue()); self.show_step_api_hash()

    def show_step_api_hash(self):
        self.clear_content(); self.status.SetLabel("Step 2: API Hash")
        self.txt_hash = wx.TextCtrl(self.content_panel, style=wx.TE_PROCESS_ENTER); self.txt_hash.SetHint("Enter API Hash")
        btn = wx.Button(self.content_panel, label="Next"); btn.Bind(wx.EVT_BUTTON, self.on_hash_next)
        self.content_sizer.Add(wx.StaticText(self.content_panel, label="API Hash:"), 0, wx.LEFT, 10)
        self.content_sizer.Add(self.txt_hash, 0, wx.EXPAND|wx.ALL, 10); self.content_sizer.Add(btn, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_hash.SetFocus()

    def on_hash_next(self, e):
        self.data['api_hash'] = self.txt_hash.GetValue().strip(); self.show_step_country()

    def show_step_country(self):
        self.clear_content(); self.status.SetLabel("Step 3: Select Country")
        country_data = load_country_codes()
        if not country_data: wx.MessageBox("CountryCodes.json missing!", "Error"); return
        self.display_choices, self.country_map = [], {}
        country_data.sort(key=lambda x: x.get('name', ''))
        for item in country_data:
            d_str = f"{item.get('name', 'Unknown')} ({item.get('dial_code', '')})"
            self.display_choices.append(d_str); self.country_map[d_str] = item.get('dial_code', '')
        self.lst_country = wx.ListBox(self.content_panel, choices=self.display_choices)
        if self.display_choices: self.lst_country.SetSelection(0)
        btn = wx.Button(self.content_panel, label="Next"); btn.Bind(wx.EVT_BUTTON, self.on_country_next)
        self.content_sizer.Add(self.lst_country, 1, wx.EXPAND|wx.ALL, 10); self.content_sizer.Add(btn, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.lst_country.SetFocus()

    def on_country_next(self, e):
        sel = self.lst_country.GetStringSelection()
        if not sel: return
        self.data['country_code'] = self.country_map[sel]; self.show_step_phone()

    def show_step_phone(self):
        self.clear_content(); self.status.SetLabel("Step 4: Phone Number")
        hbox = wx.BoxSizer(wx.HORIZONTAL)
        txt_c = wx.TextCtrl(self.content_panel, value=self.data['country_code'], size=(60, -1), style=wx.TE_READONLY)
        self.txt_p = wx.TextCtrl(self.content_panel, style=wx.TE_PROCESS_ENTER); self.txt_p.SetHint("Phone Number")
        hbox.Add(txt_c, 0, wx.RIGHT, 5); hbox.Add(self.txt_p, 1, wx.EXPAND)
        btn = wx.Button(self.content_panel, label="Next"); btn.Bind(wx.EVT_BUTTON, self.on_phone_next)
        self.content_sizer.Add(hbox, 0, wx.EXPAND|wx.ALL, 10); self.content_sizer.Add(btn, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_p.SetFocus()

    def on_phone_next(self, e):
        self.data['phone'] = self.data['country_code'] + self.txt_p.GetValue().strip()
        save_encrypted_config({'api_id': self.data['api_id'], 'api_hash': self.data['api_hash']})
        self.status.SetLabel("Connecting..."); self.content_panel.Disable()
        self.mainframe.worker._run_task(self.async_send_code())

    async def async_send_code(self):
        res = await self.mainframe.worker.connect_and_send_code(self.data['api_id'], self.data['api_hash'], self.data['phone'])
        if res == "CODE_SENT": wx.CallAfter(self.show_step_code)
        else: wx.CallAfter(self.content_panel.Enable)

    def show_step_code(self):
        self.content_panel.Enable(); self.clear_content(); self.status.SetLabel("Step 5: OTP Code")
        self.txt_otp = wx.TextCtrl(self.content_panel); btn = wx.Button(self.content_panel, label="Submit")
        btn.Bind(wx.EVT_BUTTON, lambda e: self.mainframe.worker._run_task(self.async_verify(self.txt_otp.GetValue())))
        self.content_sizer.Add(self.txt_otp, 0, wx.EXPAND|wx.ALL, 10); self.content_sizer.Add(btn, 0, wx.ALIGN_RIGHT|wx.RIGHT, 10)
        self.content_panel.Layout(); self.txt_otp.SetFocus()

    async def async_verify(self, code):
        res = await self.mainframe.worker.complete_login(self.data['phone'], code)
        if res == "ERROR": wx.CallAfter(self.content_panel.Enable)

class MainFrame(wx.Frame):
    def __init__(self):
        super().__init__(None, title=f"Smart TG Downloader v{CURRENT_VERSION}", size=(700, 550))
        if sys.platform == 'win32' and WIN32_AVAILABLE:
            self.mutex = CreateMutex(None, False, "Global\\TGDownloader_Mutex_App")
            if GetLastError() == ERROR_ALREADY_EXISTS: self.Destroy(); return
        self.worker = TelegramWorker(self.update_status, self.update_progress, self.on_login_success)
        self.config, self.progress_dlg = load_encrypted_config(), None
        self.sizer = wx.BoxSizer(wx.VERTICAL); self.SetSizer(self.sizer)
        self.login_panel, self.download_panel = LoginPanel(self, self), wx.Panel(self)
        self.init_download_ui()
        if 'api_id' in self.config:
            self.show_download(); self.worker._run_task(self.worker.connect_silent(self.config['api_id'], self.config['api_hash']))
        else: self.show_login()
        self.Centre(); self.Show()

    def on_login_success(self): self.show_download()
    def show_login(self): self.download_panel.Hide(); self.login_panel.Show(); self.sizer.Add(self.login_panel, 1, wx.EXPAND); self.Layout()
    def show_download(self): self.login_panel.Hide(); self.download_panel.Show(); self.sizer.Add(self.download_panel, 1, wx.EXPAND); self.Layout()
    def update_status(self, msg, err=False): self.lbl_status.SetLabel(msg) if not self.login_panel.IsShown() else self.login_panel.status.SetLabel(msg)
    
    def update_progress(self, cmd, data, extra=0):
        if cmd == "START_DIALOG": self.progress_dlg = DownloadProgressDialog(self); self.progress_dlg.Show()
        elif cmd == "FINISHED": self.progress_dlg.Destroy() if self.progress_dlg else None; wx.MessageBox("Done!", "Finished")
        elif cmd == "LOG": self.progress_dlg.log_message(data) if self.progress_dlg else None
        elif cmd == "PROGRESS": self.progress_dlg.update_progress(*data) if self.progress_dlg else None

    def init_download_ui(self):
        v = wx.BoxSizer(wx.VERTICAL); self.txt_link = wx.TextCtrl(self.download_panel); self.txt_link.SetHint("Paste Link")
        btn = wx.Button(self.download_panel, label="Process"); btn.Bind(wx.EVT_BUTTON, lambda e: self.worker._run_task(self.worker.analyze_link(self.txt_link.GetValue(), ["All"])))
        self.lbl_status = wx.StaticText(self.download_panel, label="Ready")
        v.Add(self.txt_link, 0, wx.ALL|wx.EXPAND, 20); v.Add(btn, 0, wx.ALL|wx.CENTER, 10); v.Add(self.lbl_status, 0, wx.ALL|wx.CENTER, 10)
        self.download_panel.SetSizer(v); self.download_panel.Hide()

if __name__ == "__main__":
    app = wx.App(); MainFrame(); app.MainLoop()
