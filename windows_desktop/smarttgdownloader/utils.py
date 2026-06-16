import wx
try:
    import wx.adv as wxadv
except Exception:
    wxadv = None
import sys
import os
import json
import asyncio
import threading
import shutil
import re
import traceback
import datetime
import time
import ctypes
from urllib.parse import urlparse, parse_qs, unquote
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
from telethon.tl.functions.messages import ImportChatInviteRequest, CheckChatInviteRequest
from cryptography.fernet import Fernet

CURRENT_VERSION = "8.7.0"
DOWNLOAD_BATCH_SIZE = 4

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

def _resource_search_paths():
    paths = []

    # PyInstaller onefile extraction dir
    if hasattr(sys, "_MEIPASS"):
        paths.append(Path(sys._MEIPASS))

    # Nuitka onefile/standalone path where bundled files are unpacked
    try:
        paths.append(Path(__file__).resolve().parent)
    except Exception:
        pass

    # Executable directory when frozen
    if getattr(sys, "frozen", False):
        try:
            paths.append(Path(sys.executable).resolve().parent)
        except Exception:
            pass
        try:
            paths.append(Path(sys.argv[0]).resolve().parent)
        except Exception:
            pass

    # Development and runtime fallbacks
    paths.append(Path.cwd())
    paths.append(get_appdata_root())

    # Preserve order while removing duplicates
    unique = []
    seen = set()
    for path in paths:
        key = str(path).lower()
        if key not in seen:
            seen.add(key)
            unique.append(path)
    return unique

def get_resource_path(filename):
    for base_path in _resource_search_paths():
        resource_path = base_path / filename
        if resource_path.exists():
            return resource_path
    return get_appdata_root() / filename

def get_first_available_resource(filenames):
    for filename in filenames:
        path = get_resource_path(filename)
        if path.exists():
            return path
    return get_resource_path(filenames[0])

APPDATA_ROOT = get_appdata_root()
SESSION_FILE = APPDATA_ROOT / "user_session"
CONFIG_FILE_ENC = APPDATA_ROOT / "config.enc"
KEY_FILE = APPDATA_ROOT / "key.key"
SETTINGS_FILE = APPDATA_ROOT / "settings.json"
ACCOUNTS_FILE = APPDATA_ROOT / "accounts.json"
COUNTRY_FILE_CANDIDATES = ("CountryCodes.json", "country.json")
COUNTRY_FILE = get_first_available_resource(COUNTRY_FILE_CANDIDATES)
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

FERNET = None

def _get_fernet():
    global FERNET
    if FERNET is None:
        FERNET = Fernet(load_or_create_key())
    return FERNET

def save_encrypted_config(data: dict):
    raw = json.dumps(data).encode("utf-8")
    token = _get_fernet().encrypt(raw)
    CONFIG_FILE_ENC.write_bytes(token)

def load_encrypted_config() -> dict:
    if not CONFIG_FILE_ENC.exists(): return {}
    try:
        token = CONFIG_FILE_ENC.read_bytes()
        raw = _get_fernet().decrypt(token)
        return json.loads(raw.decode("utf-8"))
    except: return {}

def _runtime_embedded_api_credentials():
    try:
        from ._secrets import EMBEDDED_API_ID, EMBEDDED_API_HASH
        return EMBEDDED_API_ID, EMBEDDED_API_HASH
    except ImportError:
        return None, None

def resolve_api_credentials():
    try:
        embedded_id, embedded_hash = _runtime_embedded_api_credentials()
    except Exception:
        embedded_id, embedded_hash = None, None
    api_id = os.getenv("TG_API_ID") or embedded_id
    api_hash = os.getenv("TG_API_HASH") or embedded_hash

    try:
        api_id = int(str(api_id).strip()) if api_id is not None else None
    except Exception:
        api_id = None

    if not api_hash:
        api_hash = None
    else:
        api_hash = str(api_hash).strip()
        if not api_hash:
            api_hash = None

    return api_id, api_hash

# --- Settings Manager ---
def save_settings(data: dict):
    current = load_settings()
    current.update(data)
    current.pop("recent_links", None)
    current.pop("max_parallel_downloads", None)
    SETTINGS_FILE.write_text(json.dumps(current), encoding='utf-8')

def load_settings() -> dict:
    defaults = {
        'proxy_enabled': False, 
        'proxy_type': 'SOCKS5',
        'proxy_host': '127.0.0.1', 
        'proxy_port': 1080,
        'speed_limit_kbps': 0,
    }
    if not SETTINGS_FILE.exists(): return defaults
    try:
        saved = json.loads(SETTINGS_FILE.read_text(encoding='utf-8'))
        defaults.update(saved)
        return defaults
    except: return defaults

def load_accounts_registry():
    if not ACCOUNTS_FILE.exists():
        return []
    try:
        data = json.loads(ACCOUNTS_FILE.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            return []
        cleaned = []
        for row in data:
            if not isinstance(row, dict):
                continue
            account_id = str(row.get("id", "")).strip()
            label = str(row.get("label", "")).strip()
            session = str(row.get("session", "")).strip()
            if account_id and label and session:
                cleaned.append({"id": account_id, "label": label, "session": session})
        return cleaned
    except Exception:
        return []

def save_accounts_registry(accounts):
    ACCOUNTS_FILE.write_text(json.dumps(accounts, ensure_ascii=True, indent=2), encoding="utf-8")

def account_session_path(account):
    return APPDATA_ROOT / account["session"]

def session_exists_for_account(account):
    base = account_session_path(account)
    return base.exists() or Path(f"{base}.session").exists()

def make_account_id(label, existing_ids):
    base = re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_") or "account"
    candidate = base
    idx = 2
    while candidate in existing_ids:
        candidate = f"{base}_{idx}"
        idx += 1
    return candidate

def ensure_accounts_registry():
    accounts = load_accounts_registry()
    if accounts:
        return accounts
    default = {"id": "account_1", "label": "Account 1", "session": "user_session"}
    save_accounts_registry([default])
    return [default]

def validate_and_repair_accounts(accounts, active_id):
    notes = []
    repaired = []
    seen_ids = set()
    for row in accounts:
        if not isinstance(row, dict):
            continue
        account_id = str(row.get("id", "")).strip()
        label = str(row.get("label", "")).strip()
        session = str(row.get("session", "")).strip()
        if not account_id or not session:
            notes.append("Removed invalid account entry.")
            continue
        if account_id in seen_ids:
            notes.append(f"Removed duplicate account id: {account_id}.")
            continue
        seen_ids.add(account_id)
        if not label:
            label = f"Account {len(repaired) + 1}"
            notes.append(f"Repaired missing label for {account_id}.")
        repaired.append({"id": account_id, "label": label, "session": session})

    if not repaired:
        repaired = [{"id": "account_1", "label": "Account 1", "session": "user_session"}]
        notes.append("Created default account.")

    valid_ids = {a["id"] for a in repaired}
    if active_id not in valid_ids:
        old = active_id or "none"
        active_id = repaired[0]["id"]
        notes.append(f"Active account fixed: {old} -> {active_id}.")

    return repaired, active_id, notes

def purge_legacy_credential_cache():
    for path in (CONFIG_FILE_ENC, KEY_FILE):
        try:
            if path.exists():
                path.unlink()
        except Exception:
            pass

def load_country_codes():
    country_file = get_first_available_resource(COUNTRY_FILE_CANDIDATES)
    if not country_file.exists(): return []
    try:
        with open(country_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
            if not isinstance(data, list):
                return []

            cleaned = []
            for row in data:
                if not isinstance(row, dict):
                    continue
                name = str(row.get("name", "")).strip()
                dial_code = str(row.get("dial_code", "")).strip()
                if name and dial_code:
                    cleaned.append({"name": name, "dial_code": dial_code})
            return cleaned
    except: return []

class NVDASpeech:
    _load_attempted = False
    _dll = None
    _last_msg = ""
    _last_ts = 0.0
    _dedupe_seconds = 2.5
    _lock = threading.Lock()

    @classmethod
    def _load(cls):
        if cls._load_attempted:
            return cls._dll is not None
        cls._load_attempted = True

        dll_path = get_resource_path("nvdaControllerClient64.dll")
        if not dll_path.exists():
            return False

        try:
            dll = ctypes.WinDLL(str(dll_path))
            dll.nvdaController_testIfRunning.restype = ctypes.c_int
            dll.nvdaController_speakText.argtypes = [ctypes.c_wchar_p]
            dll.nvdaController_speakText.restype = ctypes.c_int
            cls._dll = dll
            return True
        except Exception:
            cls._dll = None
            return False

    @classmethod
    def speak(cls, message):
        text = str(message or "").strip()
        if not text:
            return False
        if not cls._load():
            return False

        dedupe_text = re.sub(r"\s+", " ", text).strip().lower()
        now = time.monotonic()
        with cls._lock:
            if dedupe_text == cls._last_msg and (now - cls._last_ts) < cls._dedupe_seconds:
                return True
            # Reserve this message before speaking to avoid duplicate parallel calls.
            previous_msg, previous_ts = cls._last_msg, cls._last_ts
            cls._last_msg = dedupe_text
            cls._last_ts = now

        try:
            if cls._dll.nvdaController_testIfRunning() != 0:
                return False
            result = cls._dll.nvdaController_speakText(text)
            if result == 0:
                return True
            with cls._lock:
                cls._last_msg, cls._last_ts = previous_msg, previous_ts
            return False
        except Exception:
            with cls._lock:
                cls._last_msg, cls._last_ts = previous_msg, previous_ts
            return False

# --- Accessibility Helper (equivalent to ARIA/aria-live for wxPython) ---
class AccessibilityHelper:
    """Helper class for accessibility announcements (equivalent to aria-live regions).
    
    In wxPython, we don't have ARIA directly, but we can achieve similar functionality
    using accessible names, tooltips, and message boxes for important announcements.
    """
    
    _last_announce_key = ""
    _last_announce_ts = 0.0
    _announce_dedupe_seconds = 2.5
    _announce_lock = threading.Lock()

    @staticmethod
    def announce(parent, message, priority="polite"):
        """Announce a message to screen readers.
        
        Args:
            parent: The parent window for context
            message: The message to announce
            priority: "polite" (non-urgent) or "assertive" (urgent/errors)
        """
        text = str(message or "").strip()
        if not text:
            return
        normalized = re.sub(r"\s+", " ", text).strip().lower()
        dedupe_key = normalized
        now = time.monotonic()
        with AccessibilityHelper._announce_lock:
            if dedupe_key == AccessibilityHelper._last_announce_key and (now - AccessibilityHelper._last_announce_ts) < AccessibilityHelper._announce_dedupe_seconds:
                return
            AccessibilityHelper._last_announce_key = dedupe_key
            AccessibilityHelper._last_announce_ts = now

        # For assertive announcements (errors), use wx.Bell for audio feedback
        if priority == "assertive":
            wx.Bell()

        # Prefer direct NVDA speech when nvdaControllerClient64.dll is available.
        if NVDASpeech.speak(text):
            return

        # Create a temporary static text that screen readers will pick up
        # This is the wxPython equivalent of aria-live regions
        if hasattr(parent, '_live_region'):
            parent._live_region.SetLabel(text)
        else:
            # Create a hidden live region for announcements
            parent._live_region = wx.StaticText(parent, label=text)
            parent._live_region.SetName(text)
            # Position off-screen but accessible to screen readers
            parent._live_region.SetSize((1, 1))
    
    @staticmethod
    def set_accessible_properties(widget, name, description=None, role_description=None):
        """Set accessible properties on a widget (equivalent to aria-label, aria-describedby).
        
        Args:
            widget: The wxPython widget
            name: Accessible name (like aria-label)
            description: Additional description (like aria-describedby)
            role_description: Custom role description (like aria-roledescription)
        """
        if name:
            widget.SetName(name)
        if description:
            widget.SetToolTip(description)
            # Also set the hint for text controls
            if hasattr(widget, 'SetHint') and not getattr(widget, "GetHint", lambda: "")():
                widget.SetHint(description)

    @staticmethod
    def bind_focus_announcement(widget, parent, message, priority="polite"):
        """Announce context whenever a control receives focus."""
        def _on_focus(event):
            wx.CallAfter(AccessibilityHelper.announce, parent, message, priority)
            event.Skip()

        widget.Bind(wx.EVT_SET_FOCUS, _on_focus)
    
    @staticmethod
    def announce_status_change(parent, old_status, new_status):
        """Announce when a status changes (for progress updates)."""
        if old_status != new_status:
            AccessibilityHelper.announce(parent, f"Status: {new_status}")
    
    @staticmethod
    def announce_error(parent, error_message):
        """Announce an error with assertive priority."""
        text = str(error_message or "").strip()
        text = re.sub(r"^\s*error\s*[:\-]\s*", "", text, flags=re.IGNORECASE)
        AccessibilityHelper.announce(parent, f"Error: {text}", priority="assertive")

# --- Helper Function (PURE) ---
def _clean_filename(msg):
    """Pure helper function to extract filename from message object."""
    fname = "Unknown"

    media = getattr(msg, "media", None)
    webpage = getattr(media, "webpage", None) if media else None
    doc = getattr(msg, "document", None) or getattr(webpage, "document", None)

    if doc:
        for attr in getattr(doc, "attributes", []):
            if isinstance(attr, types.DocumentAttributeFilename):
                fname = attr.file_name
                break
            if isinstance(attr, types.DocumentAttributeAudio):
                p = attr.performer or ""
                if p.strip().lower() == "unknown": p = ""
                t = attr.title or "Audio"
                mime = ""
                msg_file = getattr(msg, "file", None)
                if msg_file:
                    mime = (getattr(msg_file, "mime_type", None) or "").lower()
                if not mime:
                    mime = (getattr(doc, "mime_type", None) or "").lower()
                ext = ".mp3" if mime == "audio/mpeg" else ".m4a"
                if p: fname = f"{p} - {t}{ext}"
                else: fname = f"{t}{ext}"
                break
    
    if fname == "Unknown":
        if getattr(msg, "photo", None) or getattr(webpage, "photo", None):
            fname = f"Photo_{getattr(msg, 'id', 'unknown')}.jpg"
        else:
            mime = ""
            msg_file = getattr(msg, "file", None)
            if msg_file:
                mime = (getattr(msg_file, "mime_type", None) or "").lower()
            if not mime and doc:
                mime = (getattr(doc, "mime_type", None) or "").lower()
            ext = ".bin"
            if mime and "/" in mime:
                ext_candidate = mime.split("/", 1)[1].split(";", 1)[0].strip()
                if ext_candidate:
                    ext = f".{ext_candidate}"
            fname = f"File_{getattr(msg, 'id', 'unknown')}{ext}"

    cleaned = re.sub(r'[<>:"/\\|?*]', "_", str(fname)).strip().strip(".")
    return cleaned or f"File_{getattr(msg, 'id', 'unknown')}.bin"

