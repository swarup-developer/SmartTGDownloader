from .utils import *
from .worker import TelegramWorker

# --- Progress Dialog Class ---
class DownloadProgressDialog(wx.Dialog):
    def __init__(self, parent):
        super().__init__(parent, title="Processing Downloads...", size=(500, 350))
        self.parent = parent
        self.stop_requested = False
        self._last_announced_percent = -1  # Track for accessibility announcements
        
        panel = wx.Panel(self)
        vbox = wx.BoxSizer(wx.VERTICAL)
        
        self.lbl_current = wx.StaticText(panel, label="Initializing...")
        AccessibilityHelper.set_accessible_properties(
            self.lbl_current, 
            "Download Status", 
            "Shows the current download file and progress"
        )
        font = self.lbl_current.GetFont()
        font.MakeBold()
        self.lbl_current.SetFont(font)
        vbox.Add(self.lbl_current, 0, wx.ALL|wx.EXPAND, 10)
        
        self.gauge = wx.Gauge(panel, range=100, size=(250, 25))
        AccessibilityHelper.set_accessible_properties(
            self.gauge, 
            "Download Progress", 
            "Progress bar showing download completion percentage"
        )
        vbox.Add(self.gauge, 0, wx.ALL|wx.EXPAND, 10)
        
        lbl_details = wx.StaticText(panel, label="Details:")
        vbox.Add(lbl_details, 0, wx.LEFT, 10)
        self.txt_log = wx.TextCtrl(panel, style=wx.TE_MULTILINE|wx.TE_READONLY|wx.HSCROLL)
        AccessibilityHelper.set_accessible_properties(
            self.txt_log, 
            "Download Log", 
            "Log of download activity and status messages"
        )
        vbox.Add(self.txt_log, 1, wx.ALL|wx.EXPAND, 10)
        
        self.btn_stop = wx.Button(panel, label="Stop Download")
        AccessibilityHelper.set_accessible_properties(
            self.btn_stop, 
            "Stop Download Button", 
            "Press to stop the current download"
        )
        self.btn_stop.Bind(wx.EVT_BUTTON, self.on_stop)
        btn_row = wx.BoxSizer(wx.HORIZONTAL)
        btn_row.Add(self.btn_stop, 0, wx.ALL, 5)

        self.btn_pause = wx.Button(panel, label="Pause All")
        AccessibilityHelper.set_accessible_properties(
            self.btn_pause,
            "Pause all downloads button",
            "Pause or resume all ongoing downloads"
        )
        self.btn_pause.Bind(wx.EVT_BUTTON, self.on_pause_resume)
        self.is_paused = False
        btn_row.Add(self.btn_pause, 0, wx.ALL, 5)

        vbox.Add(btn_row, 0, wx.ALL|wx.CENTER, 5)
        
        panel.SetSizer(vbox)
        self.CenterOnParent()
        
        # Announce dialog opened
        AccessibilityHelper.announce(self, "Download started. Processing files.")

    def on_stop(self, event):
        self.stop_requested = True
        self.lbl_current.SetLabel("Stopping... Please wait.")
        AccessibilityHelper.announce(self, "Stopping download. Please wait.", priority="assertive")
        self.btn_stop.Disable()
        self.btn_pause.Disable()
        if self.parent.worker:
            self.parent.worker.stop_download()

    def on_pause_resume(self, event):
        if not self.parent.worker:
            return
        if self.is_paused:
            self.parent.worker.resume_downloads()
        else:
            self.parent.worker.pause_downloads()

    def set_paused_state(self, is_paused):
        self.is_paused = bool(is_paused)
        self.btn_pause.SetLabel("Resume All" if self.is_paused else "Pause All")
        if self.is_paused:
            self.lbl_current.SetLabel("Paused. Click Resume All to continue.")

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
        status_text = f"Downloading: {filename} ({current_mb} / {total_mb})"
        self.lbl_current.SetLabel(status_text)
        self.gauge.SetValue(percent)
        
        # Announce progress at 25%, 50%, 75%, and 100% for screen readers
        announce_points = [25, 50, 75, 100]
        for point in announce_points:
            if percent >= point and self._last_announced_percent < point:
                AccessibilityHelper.announce(self, f"Download {percent}% complete. {filename}")
                self._last_announced_percent = percent
                break

    def log_message(self, message):
        self.txt_log.AppendText(f"{message}\n")
        # Announce important messages (errors, completions, etc.)
        if "Error" in message or "Completed" in message or "Finished" in message:
            priority = "assertive" if "Error" in message else "polite"
            AccessibilityHelper.announce(self, message, priority=priority)

class ScanResultsDialog(wx.Dialog):
    def __init__(self, parent, summary):
        super().__init__(
            parent,
            title="Scan Results",
            size=(860, 620),
            style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER
        )
        self.summary = summary or {}
        self.items = self.summary.get("items", [])
        self.item_map = {item["key"]: item for item in self.items}
        self._last_selection_announcement = ""

        panel = wx.Panel(self)
        root = wx.BoxSizer(wx.VERTICAL)

        title = wx.StaticText(panel, label=f"Source: {self.summary.get('title', 'Unknown')}")
        tf = title.GetFont()
        tf.PointSize += 1
        tf.Weight = wx.FONTWEIGHT_BOLD
        title.SetFont(tf)
        root.Add(title, 0, wx.ALL | wx.EXPAND, 10)

        counts = self.summary.get("counts", {})
        total_files = int(self.summary.get("count", 0))
        total_size = int(self.summary.get("total_size", 0))
        estimate_seconds = int(self.summary.get("estimated_seconds", 0))

        summary_text = (
            f"Total files: {total_files}    "
            f"Total size: {self._format_bytes(total_size)}    "
            f"Estimated time: {self._format_duration(estimate_seconds)}\n"
            f"Audio: {counts.get('Audio', 0)} | "
            f"Video: {counts.get('Video', 0)} | "
            f"Images: {counts.get('Images', 0)} | "
            f"Docs: {counts.get('Docs', 0)}"
        )
        self.lbl_summary = wx.StaticText(panel, label=summary_text)
        self.lbl_summary.Wrap(820)
        root.Add(self.lbl_summary, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.EXPAND, 10)

        self.lst_files = wx.CheckListBox(
            panel,
            choices=[self._item_label(item) for item in self.items]
        )
        for idx in range(len(self.items)):
            self.lst_files.Check(idx, True)
        self.lst_files.Bind(wx.EVT_CHECKLISTBOX, self.on_selection_change)
        self.lst_files.Bind(wx.EVT_CHAR_HOOK, self.on_key_shortcuts)
        self.Bind(wx.EVT_CHAR_HOOK, self.on_key_shortcuts)
        root.Add(self.lst_files, 1, wx.ALL | wx.EXPAND, 10)

        self.lbl_selected = wx.StaticText(panel, label="")
        root.Add(self.lbl_selected, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.EXPAND, 10)
        self._refresh_selected_summary()
        root.Add(
            wx.StaticText(panel, label="Shortcuts: A = Select All, N = Select None, Space = Toggle current item"),
            0,
            wx.LEFT | wx.RIGHT | wx.BOTTOM,
            10
        )

        button_row = wx.BoxSizer(wx.HORIZONTAL)
        btn_select_all = wx.Button(panel, label="Select All")
        btn_clear = wx.Button(panel, label="Clear All")
        btn_download = wx.Button(panel, wx.ID_OK, label="Download Selected")
        btn_cancel = wx.Button(panel, wx.ID_CANCEL, label="Cancel")

        btn_select_all.Bind(wx.EVT_BUTTON, self.on_select_all)
        btn_clear.Bind(wx.EVT_BUTTON, self.on_clear_all)
        btn_download.Bind(wx.EVT_BUTTON, self.on_download_selected)

        button_row.Add(btn_select_all, 0, wx.RIGHT, 8)
        button_row.Add(btn_clear, 0, wx.RIGHT, 12)
        button_row.AddStretchSpacer(1)
        button_row.Add(btn_download, 0, wx.RIGHT, 8)
        button_row.Add(btn_cancel, 0)
        root.Add(button_row, 0, wx.ALL | wx.EXPAND, 10)

        panel.SetSizer(root)
        self.SetMinSize((700, 480))
        self.CenterOnParent()
        wx.CallAfter(self.lst_files.SetFocus)

    def _item_label(self, item):
        return f"[{item.get('type', 'Unknown')}] {item.get('name', 'Unknown')} ({self._format_bytes(int(item.get('size', 0) or 0))})"

    def _refresh_selected_summary(self):
        selected_keys = self.get_selected_keys()
        selected_size = sum(int(self.item_map[k].get("size", 0) or 0) for k in selected_keys if k in self.item_map)
        msg = f"Selected files: {len(selected_keys)}    Selected size: {self._format_bytes(selected_size)}"
        self.lbl_selected.SetLabel(msg)
        if msg != self._last_selection_announcement:
            self._last_selection_announcement = msg
            AccessibilityHelper.announce(self, msg)

    def on_selection_change(self, event):
        self._refresh_selected_summary()
        event.Skip()

    def on_select_all(self, event):
        for idx in range(len(self.items)):
            self.lst_files.Check(idx, True)
        self._refresh_selected_summary()

    def on_clear_all(self, event):
        for idx in range(len(self.items)):
            self.lst_files.Check(idx, False)
        self._refresh_selected_summary()

    def on_download_selected(self, event):
        selected = self.get_selected_keys()
        if not selected:
            self.lbl_selected.SetLabel("Select at least one file to continue.")
            AccessibilityHelper.announce_error(self, "Select at least one file to continue.")
            return
        self.EndModal(wx.ID_OK)

    def on_key_shortcuts(self, event):
        key = event.GetKeyCode()
        if key in (ord("A"), ord("a")):
            self.on_select_all(None)
            return
        if key in (ord("N"), ord("n")):
            self.on_clear_all(None)
            return
        if key == wx.WXK_SPACE:
            idx = self.lst_files.GetSelection()
            if idx != wx.NOT_FOUND:
                self.lst_files.Check(idx, not self.lst_files.IsChecked(idx))
                self._refresh_selected_summary()
            return
        event.Skip()

    def get_selected_keys(self):
        selected = []
        for idx, item in enumerate(self.items):
            if self.lst_files.IsChecked(idx):
                selected.append(item["key"])
        return selected

    @staticmethod
    def _format_bytes(size):
        size = int(size or 0)
        units = ["B", "KB", "MB", "GB", "TB"]
        unit_idx = 0
        value = float(size)
        while value >= 1024 and unit_idx < len(units) - 1:
            value /= 1024
            unit_idx += 1
        return f"{value:.2f} {units[unit_idx]}"

    @staticmethod
    def _format_duration(seconds):
        seconds = int(max(0, seconds))
        h, rem = divmod(seconds, 3600)
        m, s = divmod(rem, 60)
        if h:
            return f"{h}h {m}m {s}s"
        if m:
            return f"{m}m {s}s"
        return f"{s}s"


class HelpListDialog(wx.Dialog):
    def __init__(self, parent):
        super().__init__(
            parent,
            title="Help - Smart TG Downloader",
            size=(700, 540),
            style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER
        )
        p = wx.Panel(self)
        v = wx.BoxSizer(wx.VERTICAL)
        
        help_text = """Smart TG Downloader - Help

Overview
Smart TG Downloader analyzes Telegram links and downloads media in bulk with filtering and resume support.

Login Flow
1. Select your country from the dropdown.
2. Enter your mobile number.
3. Click Get OTP.
4. Enter OTP and click Login.
5. If your Telegram account has an authentication password, enter it in the 2FA prompt.

Analyze and Download Flow
1. Paste a Telegram link.
2. Click Analyze Link.
3. Choose content filters (Audio, Video, Images, Docs, or All).
4. Wait for scan completion.
5. Review scan results and start download.

Supported Links
- Public channel links (t.me/channelname)
- Private/invite links (t.me/+invite, t.me/joinchat/...)
- Internal private links (t.me/c/...)
- Bot links (t.me/botusername)

Key Features
- Filtered download by media type
- Scan-first workflow before downloading
- Natural file sorting
- Resume support for interrupted downloads
- Duplicate skip for existing files

Copyright
Copyright (c) 2026 Smart TG Downloader.
All rights reserved."""

        self.txt_help = wx.TextCtrl(
            p,
            value=help_text,
            style=wx.TE_MULTILINE | wx.TE_READONLY | wx.TE_RICH2
        )
        AccessibilityHelper.set_accessible_properties(
            self.txt_help,
            "Help and instructions",
            "Read-only instructions for login and downloads"
        )
        v.Add(self.txt_help, 1, wx.EXPAND|wx.ALL, 10)

        btn_close = wx.Button(p, wx.ID_CLOSE, "Close")
        AccessibilityHelper.set_accessible_properties(
            btn_close,
            "Close Help Button",
            "Close this help dialog"
        )
        btn_close.SetDefault()
        v.Add(btn_close, 0, wx.CENTER|wx.BOTTOM, 10)

        p.SetSizer(v)
        self.SetMinSize((560, 420))
        self.SetEscapeId(wx.ID_CLOSE)
        self.CenterOnParent()
        wx.CallAfter(self.txt_help.SetFocus)

class AccountSwitchDialog(wx.Dialog):
    def __init__(self, parent, accounts, active_account_id=None):
        super().__init__(
            parent,
            title="Switch Account",
            size=(480, 360),
            style=wx.DEFAULT_DIALOG_STYLE | wx.RESIZE_BORDER
        )
        self.accounts = accounts
        self.selected_account_id = None

        panel = wx.Panel(self)
        root = wx.BoxSizer(wx.VERTICAL)

        root.Add(wx.StaticText(panel, label="Choose an account or add a new one:"), 0, wx.ALL, 10)
        choices = [acc["label"] for acc in self.accounts]
        self.list_accounts = wx.ListBox(panel, choices=choices)
        if self.accounts:
            active_idx = 0
            for idx, acc in enumerate(self.accounts):
                if acc["id"] == active_account_id:
                    active_idx = idx
                    break
            self.list_accounts.SetSelection(active_idx)
        root.Add(self.list_accounts, 1, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.EXPAND, 10)
        self.lbl_status = wx.StaticText(panel, label="")
        self.lbl_status.Wrap(430)
        root.Add(self.lbl_status, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.EXPAND, 10)

        row = wx.BoxSizer(wx.HORIZONTAL)
        self.btn_use = wx.Button(panel, wx.ID_OK, label="Use Selected")
        self.btn_add = wx.Button(panel, wx.ID_ADD, label="New Account")
        self.btn_cancel = wx.Button(panel, wx.ID_CANCEL, label="Cancel")
        self.btn_use.Bind(wx.EVT_BUTTON, self.on_use_selected)
        self.btn_add.Bind(wx.EVT_BUTTON, self.on_add_account)
        row.Add(self.btn_use, 0, wx.RIGHT, 8)
        row.Add(self.btn_add, 0, wx.RIGHT, 8)
        row.AddStretchSpacer(1)
        row.Add(self.btn_cancel, 0)
        root.Add(row, 0, wx.ALL | wx.EXPAND, 10)

        panel.SetSizer(root)
        self.SetMinSize((440, 300))
        self.CenterOnParent()

    def on_use_selected(self, event):
        idx = self.list_accounts.GetSelection()
        if idx == wx.NOT_FOUND:
            self.lbl_status.SetLabel("Please select an account.")
            AccessibilityHelper.announce_error(self, "Please select an account.")
            wx.CallAfter(self.list_accounts.SetFocus)
            return
        self.selected_account_id = self.accounts[idx]["id"]
        self.EndModal(wx.ID_OK)

    def on_add_account(self, event):
        self.EndModal(wx.ID_ADD)

class LoginPanel(wx.Panel):
    def __init__(self, parent, mainframe):
        super().__init__(parent)
        self.mainframe = mainframe
        self.data = {}
        self.country_map = {}
        self.selected_country_code = ""
        self.api_id = None
        self.api_hash = None
        self.resend_cooldown_seconds = 45
        self.resend_seconds_remaining = 0

        main = wx.BoxSizer(wx.VERTICAL)
        heading = wx.StaticText(self, label="Telegram Login")
        heading_font = heading.GetFont()
        heading_font.PointSize += 5
        heading_font.Weight = wx.FONTWEIGHT_BOLD
        heading.SetFont(heading_font)
        main.Add(heading, 0, wx.ALL | wx.CENTER, 18)

        intro = wx.StaticText(
            self,
            label="Choose country, enter your mobile number, request OTP, then use Login."
        )
        intro.Wrap(620)
        main.Add(intro, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.CENTER, 16)
        self.lbl_account = wx.StaticText(self, label="Account: Account 1")
        main.Add(self.lbl_account, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.CENTER, 8)

        self.form_panel = wx.Panel(self)
        form_wrap = wx.BoxSizer(wx.VERTICAL)
        card_box = wx.StaticBoxSizer(wx.StaticBox(self.form_panel, label="Sign in to Telegram"), wx.VERTICAL)
        grid = wx.FlexGridSizer(3, 2, 8, 12)
        grid.AddGrowableCol(1, 1)

        country_data = load_country_codes()
        if not country_data:
            country_data = [{"name": "United States", "dial_code": "+1"}]
        country_data.sort(key=lambda x: x.get("name", ""))
        country_choices = []
        for row in country_data:
            label = f"{row.get('name', 'Unknown')} ({row.get('dial_code', '')})"
            country_choices.append(label)
            self.country_map[label] = row.get("dial_code", "")

        self.lbl_country = wx.StaticText(self.form_panel, label="Country:")
        self.cmb_country = wx.Choice(self.form_panel, choices=country_choices)
        if country_choices:
            self.cmb_country.SetSelection(0)
            self.selected_country_code = self.country_map[country_choices[0]]
        self.cmb_country.Bind(wx.EVT_CHOICE, self.on_country_change)
        AccessibilityHelper.set_accessible_properties(
            self.cmb_country,
            "Country dropdown",
            "Select country code for mobile number"
        )

        self.lbl_phone = wx.StaticText(self.form_panel, label="Mobile Number:")
        self.txt_phone = wx.TextCtrl(self.form_panel, style=wx.TE_PROCESS_ENTER)
        self.txt_phone.SetHint("Enter mobile number without country code")
        self.txt_phone.Bind(wx.EVT_TEXT_ENTER, self.on_get_otp)
        AccessibilityHelper.set_accessible_properties(
            self.txt_phone,
            "Mobile number input",
            "Enter mobile number without country code"
        )

        self.btn_get_otp = wx.Button(self.form_panel, label="Get OTP")
        self.btn_get_otp.Bind(wx.EVT_BUTTON, self.on_get_otp)
        AccessibilityHelper.set_accessible_properties(
            self.btn_get_otp,
            "Get OTP button",
            "Send one-time password to this Telegram number"
        )

        grid.Add(self.lbl_country, 0, wx.ALIGN_CENTER_VERTICAL)
        grid.Add(self.cmb_country, 0, wx.EXPAND)
        grid.Add(self.lbl_phone, 0, wx.ALIGN_CENTER_VERTICAL)
        grid.Add(self.txt_phone, 0, wx.EXPAND)
        grid.Add(wx.StaticText(self.form_panel, label=""), 0)
        grid.Add(self.btn_get_otp, 0, wx.ALIGN_LEFT)
        card_box.Add(grid, 0, wx.EXPAND | wx.ALL, 12)

        self.lbl_code = wx.StaticText(self.form_panel, label="OTP:")
        self.txt_code = wx.TextCtrl(self.form_panel, style=wx.TE_PROCESS_ENTER)
        self.txt_code.SetHint("Enter OTP received in Telegram")
        self.txt_code.Bind(wx.EVT_TEXT_ENTER, self.on_login_click)
        self.btn_login = wx.Button(self.form_panel, label="Login")
        self.btn_login.Bind(wx.EVT_BUTTON, self.on_login_click)
        AccessibilityHelper.set_accessible_properties(
            self.btn_login,
            "Login button",
            "Verify OTP and complete Telegram login"
        )
        otp_row = wx.BoxSizer(wx.HORIZONTAL)
        otp_row.Add(self.lbl_code, 0, wx.ALIGN_CENTER_VERTICAL | wx.RIGHT, 8)
        otp_row.Add(self.txt_code, 1, wx.RIGHT, 10)
        otp_row.Add(self.btn_login, 0)
        self.btn_resend = wx.Button(self.form_panel, label="Resend OTP")
        self.btn_resend.Bind(wx.EVT_BUTTON, self.on_resend_otp)
        otp_row.Add(self.btn_resend, 0, wx.LEFT, 8)
        card_box.Add(otp_row, 0, wx.EXPAND | wx.LEFT | wx.RIGHT | wx.BOTTOM, 12)

        self.btn_open_settings = wx.Button(self.form_panel, label="Open Settings")
        self.btn_open_settings.Bind(wx.EVT_BUTTON, self.on_open_settings)
        card_box.Add(self.btn_open_settings, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM, 12)
        self.btn_open_settings.Hide()

        form_wrap.Add(card_box, 0, wx.EXPAND | wx.ALL, 10)
        self.form_panel.SetSizer(form_wrap)
        main.Add(self.form_panel, 0, wx.EXPAND | wx.LEFT | wx.RIGHT, 24)

        self.status = wx.StaticText(self, label="Ready.")
        status_font = self.status.GetFont()
        status_font.Weight = wx.FONTWEIGHT_BOLD
        self.status.SetFont(status_font)
        self.status.Wrap(640)
        AccessibilityHelper.set_accessible_properties(self.status, "Login status")
        main.Add(self.status, 0, wx.ALL | wx.CENTER, 16)

        main.Add(wx.StaticText(self, label="Need help? Press F1."), 0, wx.ALL | wx.CENTER, 8)
        self.SetSizer(main)
        self.mobile_widgets = [
            self.lbl_country,
            self.cmb_country,
            self.lbl_phone,
            self.txt_phone,
            self.btn_get_otp
        ]
        self.otp_widgets = [self.lbl_code, self.txt_code, self.btn_login, self.btn_resend]

        self.refresh_api_credentials()
        self._set_login_stage("mobile")
        wx.CallAfter(self.txt_phone.SetFocus)

    def _destroy_wait_dialog(self):
        dlg = getattr(self, "wait_dialog", None)
        if dlg:
            try:
                dlg.Destroy()
            except Exception:
                pass
            self.wait_dialog = None

    def set_status(self, message, is_error=False):
        self.status.SetLabel(message)
        if is_error:
            AccessibilityHelper.announce_error(self, message)
        else:
            AccessibilityHelper.announce(self, message)

    def set_account_context(self, account_label):
        label = (account_label or "").strip() or "Account"
        self.lbl_account.SetLabel(f"Account: {label}")

    def reset_for_new_session(self):
        self.resend_seconds_remaining = 0
        self.txt_phone.SetValue("")
        self.txt_code.SetValue("")
        self.data = {}
        self.refresh_api_credentials()
        self._set_login_stage("mobile")
        self.form_panel.Enable()
        if self.api_id and self.api_hash:
            self.set_status("Ready. Enter mobile number and request OTP.")
        wx.CallAfter(self.txt_phone.SetFocus)

    def _set_login_stage(self, stage):
        show_mobile = stage == "mobile"
        for w in self.mobile_widgets:
            if show_mobile:
                w.Show()
            else:
                w.Hide()
        for w in self.otp_widgets:
            if show_mobile:
                w.Hide()
            else:
                w.Show()
        self.Layout()

    def refresh_api_credentials(self):
        self.api_id, self.api_hash = resolve_api_credentials()
        if self.api_id and self.api_hash:
            self.btn_get_otp.Enable()
            self.set_status("Ready. Enter mobile number and request OTP.")
        else:
            self.btn_get_otp.Disable()
            self.set_status("Login setup is unavailable. Please contact support.", is_error=True)

    def on_open_settings(self, event):
        self.mainframe.on_settings(None)
        self.refresh_api_credentials()

    def on_country_change(self, event):
        selected = self.cmb_country.GetStringSelection()
        self.selected_country_code = self.country_map.get(selected, "")

    def on_get_otp(self, event):
        selected = self.cmb_country.GetStringSelection()
        if not selected:
            self.set_status("Please select a country from the dropdown.", is_error=True)
            return
        self.selected_country_code = self.country_map.get(selected, "")

        num = self.txt_phone.GetValue().strip()
        if not num:
            self.set_status("Mobile number is required.", is_error=True)
            return
        if not num.isdigit():
            self.set_status("Mobile number must contain only digits.", is_error=True)
            return
        if len(num) < 5:
            self.set_status("Mobile number is too short.", is_error=True)
            return

        self.refresh_api_credentials()
        if not self.api_id or not self.api_hash:
            self.set_status("Login setup is unavailable right now. Please try again later.", is_error=True)
            return

        self.data["phone"] = self.selected_country_code + num
        self.set_status("Sending OTP. Please wait.")
        if hasattr(self.mainframe, "set_operation_mode"):
            self.mainframe.set_operation_mode("Ready", "Requesting OTP")
        self.wait_dialog = wx.ProgressDialog(
            "Sending OTP",
            "Please wait, requesting OTP from Telegram...",
            parent=self,
            style=wx.PD_APP_MODAL | wx.PD_AUTO_HIDE
        )
        self.wait_dialog.Pulse()
        self.form_panel.Disable()
        self.mainframe.worker._run_task(self.async_send_code())

    async def async_send_code(self):
        res = await self.mainframe.worker.connect_and_send_code(
            self.api_id, self.api_hash, self.data["phone"]
        )
        wx.CallAfter(self._handle_send_code_result, res)

    def _handle_send_code_result(self, result):
        self._destroy_wait_dialog()
        self.form_panel.Enable()

        if result == "CODE_SENT":
            self._set_login_stage("otp")
            self.txt_code.SetValue("")
            self._start_resend_cooldown()
            self.set_status("OTP sent. Enter OTP and press Login.")
            wx.CallAfter(self.txt_code.SetFocus)
            return
        if result == "ALREADY_LOGGED_IN":
            self.set_status("Successfully logged in.")
            return

        self._set_login_stage("mobile")
        self.set_status("Could not send OTP. Check details and try again.", is_error=True)

    def on_login_click(self, event):
        code = self.txt_code.GetValue().strip()
        if not code:
            self.set_status("OTP is required.", is_error=True)
            return
        if not code.isdigit():
            self.set_status("OTP must contain only digits.", is_error=True)
            return

        self.set_status("Verifying OTP. Please wait.")
        if hasattr(self.mainframe, "set_operation_mode"):
            self.mainframe.set_operation_mode("Ready", "Verifying OTP")
        self.form_panel.Disable()
        self.mainframe.worker._run_task(self.async_verify_code(code))

    async def async_verify_code(self, code):
        res = await self.mainframe.worker.complete_login(self.data["phone"], code)
        wx.CallAfter(self._handle_code_verification_result, res)

    def _handle_code_verification_result(self, result):
        if result == "2FA_REQUIRED":
            self.form_panel.Enable()
            self.prompt_two_factor_password()
            return
        if result == "ERROR":
            self.form_panel.Enable()
            self.set_status("OTP verification failed. Please try again.", is_error=True)
            return

        self.set_status("Successfully logged in.")
        if hasattr(self.mainframe, "set_operation_mode"):
            self.mainframe.set_operation_mode("Ready", "Login successful")
        wx.CallLater(120, self.mainframe.txt_link.SetFocus)

    def prompt_two_factor_password(self):
        self.set_status("Telegram authentication password required.")
        dlg = wx.TextEntryDialog(
            self,
            "Telegram authentication password:",
            "Two-Factor Authentication",
            style=wx.OK | wx.CANCEL | wx.TE_PASSWORD
        )
        result = dlg.ShowModal()
        pw = dlg.GetValue().strip() if result == wx.ID_OK else ""
        dlg.Destroy()

        if result != wx.ID_OK:
            self.set_status("Login paused. Password is required to continue.", is_error=True)
            return
        if not pw:
            self.set_status("Authentication password is required.", is_error=True)
            return

        self.set_status("Verifying Telegram authentication password...")
        self.form_panel.Disable()
        self.mainframe.worker._run_task(self.async_verify_pass(pw))

    async def async_verify_pass(self, pw):
        res = await self.mainframe.worker.complete_login(self.data["phone"], None, password=pw)
        wx.CallAfter(self._handle_pass_verification_result, res)

    def _handle_pass_verification_result(self, result):
        if result == "ERROR":
            self.form_panel.Enable()
            self.set_status("Authentication password verification failed.", is_error=True)
            return

        self.set_status("Successfully logged in.")
        if hasattr(self.mainframe, "set_operation_mode"):
            self.mainframe.set_operation_mode("Ready", "Login successful")
        wx.CallLater(120, self.mainframe.txt_link.SetFocus)

    def _start_resend_cooldown(self):
        self.resend_seconds_remaining = self.resend_cooldown_seconds
        self.btn_resend.Disable()
        self._tick_resend_cooldown()

    def _tick_resend_cooldown(self):
        if self.resend_seconds_remaining <= 0:
            self.btn_resend.SetLabel("Resend OTP")
            self.btn_resend.Enable()
            AccessibilityHelper.announce(self, "Resend OTP is available now.")
            return

        self.btn_resend.SetLabel(f"Resend OTP ({self.resend_seconds_remaining}s)")
        if self.resend_seconds_remaining % 10 == 0 or self.resend_seconds_remaining <= 5:
            AccessibilityHelper.announce(self, f"Resend OTP in {self.resend_seconds_remaining} seconds")
        self.resend_seconds_remaining -= 1
        wx.CallLater(1000, self._tick_resend_cooldown)

    def on_resend_otp(self, event):
        if self.resend_seconds_remaining > 0:
            self.set_status(f"Please wait {self.resend_seconds_remaining} seconds before resending OTP.")
            return
        if not self.data.get("phone"):
            self.set_status("Phone number missing. Return to mobile login and try again.", is_error=True)
            return
        self.set_status("Resending OTP...")
        self.btn_resend.Disable()
        self.mainframe.worker._run_task(self.async_resend_otp())

    async def async_resend_otp(self):
        res = await self.mainframe.worker.send_code_only(self.data["phone"])
        wx.CallAfter(self._handle_resend_otp_result, res)

    def _handle_resend_otp_result(self, result):
        if result == "CODE_SENT":
            self.set_status("OTP resent successfully.")
            self._start_resend_cooldown()
            wx.CallAfter(self.txt_code.SetFocus)
            return
        self.btn_resend.Enable()
        self.set_status("Could not resend OTP right now.", is_error=True)
# --- Settings Dialog (Restored: Proxy Only) ---
class SettingsDialog(wx.Dialog):
    def __init__(self, parent, worker):
        super().__init__(parent, title="Configuration", size=(560, 520))
        self.worker = worker
        self.cfg = load_settings()
        p = wx.Panel(self)
        root = wx.BoxSizer(wx.VERTICAL)

        self.notebook = wx.Notebook(p)
        self.notebook.Bind(wx.EVT_CHAR_HOOK, self.on_notebook_nav)
        self.Bind(wx.EVT_CHAR_HOOK, self.on_notebook_nav)

        self.tab_account = wx.Panel(self.notebook)
        self.tab_general = wx.Panel(self.notebook)
        self.notebook.AddPage(self.tab_account, "Account")
        self.notebook.AddPage(self.tab_general, "General")

        self._build_account_tab()
        self._build_general_tab()
        root.Add(self.notebook, 1, wx.ALL | wx.EXPAND, 10)
        self.lbl_feedback = wx.StaticText(p, label="")
        self.lbl_feedback.Wrap(520)
        root.Add(self.lbl_feedback, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.EXPAND, 10)

        action_row = wx.BoxSizer(wx.HORIZONTAL)
        btn_save = wx.Button(p, wx.ID_OK, label="Save Configuration")
        btn_close = wx.Button(p, wx.ID_CANCEL, label="Close")
        btn_save.Bind(wx.EVT_BUTTON, self.on_save)
        action_row.AddStretchSpacer(1)
        action_row.Add(btn_save, 0, wx.RIGHT, 8)
        action_row.Add(btn_close, 0)
        root.Add(action_row, 0, wx.ALL | wx.EXPAND, 10)

        p.SetSizer(root)

    def set_feedback(self, message, is_error=False):
        self.lbl_feedback.SetLabel(str(message))
        if is_error:
            AccessibilityHelper.announce_error(self, str(message))
        else:
            AccessibilityHelper.announce(self, str(message))

    def _build_account_tab(self):
        v = wx.BoxSizer(wx.VERTICAL)
        active = self.GetParent()._active_account()
        txt = wx.StaticText(self.tab_account, label=f"Current account: {active['label']}")
        v.Add(txt, 0, wx.ALL, 12)

        row = wx.BoxSizer(wx.HORIZONTAL)
        self.btn_logout = wx.Button(self.tab_account, label="Logout")
        self.btn_switch = wx.Button(self.tab_account, label="Switch Account")
        self.btn_logout.Bind(wx.EVT_BUTTON, self.on_logout)
        self.btn_switch.Bind(wx.EVT_BUTTON, self.on_switch_account)
        row.Add(self.btn_logout, 1, wx.RIGHT, 8)
        row.Add(self.btn_switch, 1)
        v.Add(row, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.EXPAND, 12)
        self.tab_account.SetSizer(v)

    def _build_general_tab(self):
        v = wx.BoxSizer(wx.VERTICAL)

        proxy_box = wx.StaticBox(self.tab_general, label="Proxy Configuration")
        proxy_sizer = wx.StaticBoxSizer(proxy_box, wx.VERTICAL)
        self.chk_proxy = wx.CheckBox(self.tab_general, label="Enable Proxy Connection")
        self.chk_proxy.SetValue(self.cfg.get('proxy_enabled', False))
        proxy_sizer.Add(self.chk_proxy, 0, wx.ALL, 10)

        proxy_grid = wx.FlexGridSizer(2, 2, 5, 10)
        proxy_grid.AddGrowableCol(1, 1)
        proxy_grid.Add(wx.StaticText(self.tab_general, label="Host:"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.txt_host = wx.TextCtrl(self.tab_general, value=self.cfg.get('proxy_host', '127.0.0.1'))
        proxy_grid.Add(self.txt_host, 0, wx.EXPAND)
        proxy_grid.Add(wx.StaticText(self.tab_general, label="Port:"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.txt_port = wx.TextCtrl(self.tab_general, value=str(self.cfg.get('proxy_port', '1080')))
        proxy_grid.Add(self.txt_port, 0, wx.EXPAND)
        proxy_sizer.Add(proxy_grid, 0, wx.EXPAND | wx.ALL, 10)
        v.Add(proxy_sizer, 0, wx.ALL | wx.EXPAND, 10)

        transfer_box = wx.StaticBox(self.tab_general, label="Transfer Controls")
        transfer_sizer = wx.StaticBoxSizer(transfer_box, wx.VERTICAL)
        transfer_grid = wx.FlexGridSizer(1, 2, 5, 10)
        transfer_grid.AddGrowableCol(1, 1)
        transfer_grid.Add(wx.StaticText(self.tab_general, label="Speed Limit KB/s (0 = unlimited):"), 0, wx.ALIGN_CENTER_VERTICAL)
        self.txt_speed = wx.TextCtrl(self.tab_general, value=str(self.cfg.get('speed_limit_kbps', 0)))
        transfer_grid.Add(self.txt_speed, 0, wx.EXPAND)
        transfer_sizer.Add(transfer_grid, 0, wx.EXPAND | wx.ALL, 10)
        transfer_sizer.Add(
            wx.StaticText(self.tab_general, label=f"Parallel downloads are fixed at {DOWNLOAD_BATCH_SIZE} per batch."),
            0,
            wx.LEFT | wx.RIGHT | wx.BOTTOM,
            10
        )
        v.Add(transfer_sizer, 0, wx.LEFT | wx.RIGHT | wx.BOTTOM | wx.EXPAND, 10)

        self.tab_general.SetSizer(v)

    def on_notebook_nav(self, event):
        key = event.GetKeyCode()
        focused = self.FindFocus()
        if isinstance(focused, wx.TextCtrl):
            event.Skip()
            return
        idx = self.notebook.GetSelection()
        count = self.notebook.GetPageCount()
        if key == wx.WXK_LEFT:
            self.notebook.ChangeSelection((idx - 1) % count)
            return
        if key == wx.WXK_RIGHT:
            self.notebook.ChangeSelection((idx + 1) % count)
            return
        event.Skip()

    def on_logout(self, event):
        self.EndModal(wx.ID_OK)
        wx.CallAfter(self.GetParent().logout_account)

    def on_switch_account(self, event):
        self.EndModal(wx.ID_OK)
        wx.CallAfter(self.GetParent().switch_account)

    def on_save(self, e):
        host = self.txt_host.GetValue().strip()
        if not host:
            self.set_feedback("Proxy host is required.", is_error=True)
            self.notebook.ChangeSelection(1)
            wx.CallAfter(self.txt_host.SetFocus)
            return

        port_text = self.txt_port.GetValue().strip()
        if not port_text.isdigit():
            self.set_feedback("Proxy port must be a number.", is_error=True)
            self.notebook.ChangeSelection(1)
            wx.CallAfter(self.txt_port.SetFocus)
            return
        port = int(port_text)
        if port < 1 or port > 65535:
            self.set_feedback("Proxy port must be between 1 and 65535.", is_error=True)
            self.notebook.ChangeSelection(1)
            wx.CallAfter(self.txt_port.SetFocus)
            return

        speed_text = self.txt_speed.GetValue().strip()
        if not speed_text.isdigit():
            self.set_feedback("Speed limit must be a number (KB/s).", is_error=True)
            self.notebook.ChangeSelection(1)
            wx.CallAfter(self.txt_speed.SetFocus)
            return
        speed_limit_kbps = int(speed_text)
        if speed_limit_kbps < 0:
            self.set_feedback("Speed limit cannot be negative.", is_error=True)
            self.notebook.ChangeSelection(1)
            wx.CallAfter(self.txt_speed.SetFocus)
            return

        data = {
            'proxy_enabled': self.chk_proxy.GetValue(),
            'proxy_type': 'SOCKS5',
            'proxy_host': host,
            'proxy_port': port,
            'speed_limit_kbps': speed_limit_kbps
        }
        save_settings(data)
        self.worker.apply_transfer_settings()
        self.set_feedback("Settings saved.")
        self.EndModal(wx.ID_OK)
class UpdateAvailableDialog(wx.Dialog):
    def __init__(self, parent, latest_version, whats_new):
        from .utils import CURRENT_VERSION
        super().__init__(parent, title="New Update Available", size=(400, 300))
        p = wx.Panel(self)
        v = wx.BoxSizer(wx.VERTICAL)
        
        lbl_title = wx.StaticText(p, label="New Update Available")
        lbl_title.SetFont(wx.Font(12, wx.FONTFAMILY_DEFAULT, wx.FONTSTYLE_NORMAL, wx.FONTWEIGHT_BOLD))
        v.Add(lbl_title, 0, wx.ALL|wx.ALIGN_LEFT, 15)
        
        lbl_version = wx.StaticText(p, label=f"Current Version: {CURRENT_VERSION}   ->   Latest Version: {latest_version}")
        v.Add(lbl_version, 0, wx.LEFT|wx.RIGHT|wx.BOTTOM, 15)
        
        lbl_whats_new = wx.StaticText(p, label="What's New:")
        lbl_whats_new.SetFont(wx.Font(9, wx.FONTFAMILY_DEFAULT, wx.FONTSTYLE_NORMAL, wx.FONTWEIGHT_BOLD))
        v.Add(lbl_whats_new, 0, wx.LEFT|wx.RIGHT|wx.BOTTOM, 5)
        
        txt_changelog = wx.TextCtrl(p, style=wx.TE_MULTILINE | wx.TE_READONLY)
        txt_changelog.SetValue(whats_new)
        v.Add(txt_changelog, 1, wx.EXPAND|wx.LEFT|wx.RIGHT|wx.BOTTOM, 15)
        
        h = wx.BoxSizer(wx.HORIZONTAL)
        b_update = wx.Button(p, label="Update Now")
        AccessibilityHelper.set_accessible_properties(
            b_update,
            "Update button",
            "Start downloading and installing update"
        )
        b_update.Bind(wx.EVT_BUTTON, lambda e: self.EndModal(wx.ID_YES))
        
        b_exit = wx.Button(p, label="Exit")
        AccessibilityHelper.set_accessible_properties(
            b_exit,
            "Exit button",
            "Exit application"
        )
        b_exit.Bind(wx.EVT_BUTTON, lambda e: self.EndModal(wx.ID_NO))
        
        h.Add(b_update, 0, wx.ALL, 5)
        h.Add(b_exit, 0, wx.ALL, 5)
        v.Add(h, 0, wx.ALIGN_RIGHT|wx.BOTTOM|wx.RIGHT, 15)
        
        p.SetSizer(v)

class MainFrame(wx.Frame):
    def __init__(self):
        super().__init__(None, title=f"Smart TG Downloader v{CURRENT_VERSION}", size=(700, 550))
        if sys.platform == 'win32' and WIN32_AVAILABLE:
            self.mutex = CreateMutex(None, False, "Global\\TGDownloader_Mutex_App")
            if GetLastError() == ERROR_ALREADY_EXISTS:
                wx.MessageBox("Application is already running.", "Error", wx.OK | wx.ICON_ERROR)
                self.Destroy(); return

        purge_legacy_credential_cache()
        self.worker = TelegramWorker(self.update_status, self.update_progress, self.on_login_success)
        self.api_id, self.api_hash = resolve_api_credentials()
        self.accounts = ensure_accounts_registry()
        cfg = load_settings()
        active_id = str(cfg.get("active_account_id", "")).strip()
        self.accounts, active_id, repair_notes = validate_and_repair_accounts(self.accounts, active_id)
        save_accounts_registry(self.accounts)
        save_settings({"active_account_id": active_id})
        self.active_account_id = active_id
        self.startup_repair_notes = repair_notes
        self.help_shown = False 
        self.progress_dlg = None
        self.is_downloading = False
        self.current_mode = "Ready"
        self.last_status_message = ""
        
        self.sizer = wx.BoxSizer(wx.VERTICAL)
        self.SetSizer(self.sizer)
        self.status_bar = self.CreateStatusBar(2)
        self.status_bar.SetStatusWidths([170, -1])
        self.set_operation_mode("Ready", "Initializing")

        self.login_panel = LoginPanel(self, self)
        self.download_panel = wx.Panel(self)
        self.init_download_ui()

        # --- Menu Setup (FIXED) ---
        self.ID_SETTINGS = wx.NewIdRef()
        
        mb = wx.MenuBar()
        fm = wx.Menu() # File Menu
        hm = wx.Menu() # Help Menu
        
        # Add Settings and Monitor with EXPLICIT IDs
        fm.Append(self.ID_SETTINGS, "&Settings\tAlt+S")
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
        self.SetAcceleratorTable(
            wx.AcceleratorTable([(wx.ACCEL_ALT, ord('S'), int(self.ID_SETTINGS))])
        )

        self.worker.set_session_file(account_session_path(self._active_account()))
        has_session = session_exists_for_account(self._active_account())
        if self.api_id and self.api_hash and has_session:
            self.show_download()
            self.update_status("Reconnecting...")
            self.worker._run_task(self.worker.connect_silent(self.api_id, self.api_hash))
        else:
            self.show_login(show_help=False, announce=False)

        self.Centre(); self.Show()
        if self.startup_repair_notes:
            note = " ".join(self.startup_repair_notes)
            self.update_status(f"Startup self-check repaired configuration: {note}")

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
        self.set_operation_mode("Ready", "Logged in successfully.")
        self.notify_user("Logged in successfully.", toast=True)
        wx.CallLater(120, self.txt_link.SetFocus)

    def show_login(self, show_help=False, announce=True):
        self.download_panel.Hide(); self.login_panel.Show()
        self.sizer.Clear(); self.sizer.Add(self.login_panel, 1, wx.EXPAND)
        active = self._active_account()
        self.login_panel.set_account_context(active.get("label", "Account"))
        self.login_panel.reset_for_new_session()
        self.Layout()
        self.Raise()
        if self.current_mode != "Logged out":
            self.set_operation_mode("Ready", "Login required")
        wx.CallAfter(self.login_panel.SetFocus)
        wx.CallLater(120, self.login_panel.txt_phone.SetFocus)
        if announce:
            wx.CallLater(180, AccessibilityHelper.announce, self.login_panel, "Login screen is ready. Enter mobile number.")
        if show_help and not self.help_shown:
            self.help_shown = True
            wx.CallAfter(self.on_help, None)

    def show_download(self):
        self.login_panel.Hide(); self.download_panel.Show()
        self.sizer.Clear(); self.sizer.Add(self.download_panel, 1, wx.EXPAND)
        self.Layout()
        self.set_operation_mode("Ready", "Paste link to analyze")
        wx.CallAfter(self.txt_link.SetFocus)

    def on_help(self, e):
        d = None
        try:
            d = HelpListDialog(self)
            d.ShowModal()
        except Exception as ex:
            AccessibilityHelper.announce_error(self, "Help window failed to open")
            self.notify_user(f"Could not open help window: {ex}", is_error=True)
        finally:
            if d:
                d.Destroy()
    
    def on_settings(self, e): 
        d = SettingsDialog(self, self.worker)
        d.ShowModal()
        d.Destroy()
        self.login_panel.refresh_api_credentials()

    def _active_account(self):
        for account in self.accounts:
            if account["id"] == self.active_account_id:
                return account
        self.active_account_id = self.accounts[0]["id"]
        return self.accounts[0]

    def _set_active_account(self, account_id):
        for account in self.accounts:
            if account["id"] == account_id:
                self.active_account_id = account_id
                save_settings({"active_account_id": account_id})
                self.worker.set_session_file(account_session_path(account))
                return True
        return False

    def _delete_session_files_for_account(self, account):
        removed = 0
        base = account_session_path(account)
        targets = [
            base,
            Path(f"{base}.session"),
            Path(f"{base}.session-journal"),
            Path(f"{base}.session-wal"),
            Path(f"{base}.session-shm"),
        ]
        for path in targets:
            try:
                if path.is_file():
                    path.unlink()
                    removed += 1
            except Exception:
                pass
        self.worker.pending_scan = None
        return removed

    def _activate_and_connect(self, account):
        self._set_active_account(account["id"])
        try:
            self.worker._run_task(self.worker.disconnect_client()).result(timeout=8)
        except Exception:
            pass

        if self.api_id and self.api_hash and session_exists_for_account(account):
            self.show_download()
            self.update_status(f"Switching to {account['label']}...")
            try:
                ok = self.worker._run_task(self.worker.connect_silent(self.api_id, self.api_hash)).result(timeout=15)
            except Exception:
                ok = False
            if not ok:
                self.show_login()
        else:
            self.show_login()

    def logout_account(self):
        account = self._active_account()
        dlg = wx.MessageDialog(
            self,
            f"This will fully log out '{account['label']}' and clear all local data for this account.\nContinue?",
            "Logout",
            wx.YES_NO | wx.ICON_QUESTION
        )
        result = dlg.ShowModal()
        dlg.Destroy()
        if result != wx.ID_YES:
            return

        try:
            self.worker._run_task(self.worker.logout_client()).result(timeout=20)
        except Exception:
            pass

        removed = self._delete_session_files_for_account(account)
        self.accounts = [a for a in self.accounts if a["id"] != account["id"]]
        if not self.accounts:
            self.accounts = ensure_accounts_registry()
        save_accounts_registry(self.accounts)
        next_account = self.accounts[0]
        self._set_active_account(next_account["id"])
        try:
            self.worker._run_task(self.worker.disconnect_client()).result(timeout=8)
        except Exception:
            pass
        self.show_login(show_help=False, announce=False)
        self.login_panel.set_status(f"Logout complete for {account['label']}.")
        self.set_operation_mode("Logged out", f"Logged out: {account['label']}")
        self.notify_user(f"Logout complete for {account['label']}.", toast=True)
        wx.CallLater(160, self.login_panel.txt_phone.SetFocus)

    def switch_account(self):
        dlg = AccountSwitchDialog(self, self.accounts, self.active_account_id)
        result = dlg.ShowModal()
        selected_id = dlg.selected_account_id
        dlg.Destroy()

        if result == wx.ID_ADD:
            self.add_account()
            return
        if result != wx.ID_OK or not selected_id:
            return

        selected = next((a for a in self.accounts if a["id"] == selected_id), None)
        if not selected:
            return
        self._activate_and_connect(selected)
        if self.login_panel.IsShown():
            self.login_panel.set_status(f"Switched to {selected['label']}.")
        self.notify_user(f"Switched to {selected['label']}.", toast=True)

    def add_account(self):
        default_name = f"Account {len(self.accounts) + 1}"
        dlg = wx.TextEntryDialog(self, "Enter display name for the new account:", "New Account", value=default_name)
        if dlg.ShowModal() != wx.ID_OK:
            dlg.Destroy()
            return
        label = dlg.GetValue().strip() or default_name
        dlg.Destroy()

        existing_ids = {a["id"] for a in self.accounts}
        account_id = make_account_id(label, existing_ids)
        session_name = f"user_session_{account_id}"
        new_account = {"id": account_id, "label": label, "session": session_name}
        self.accounts.append(new_account)
        save_accounts_registry(self.accounts)
        self._set_active_account(new_account["id"])
        try:
            self.worker._run_task(self.worker.disconnect_client()).result(timeout=8)
        except Exception:
            pass
        self.show_login(show_help=False, announce=False)
        self.login_panel.set_status(f"{label} created. Enter mobile number to continue.")
        self.set_operation_mode("Logged out", f"New account created: {label}")
        self.notify_user(f"{label} created. Enter mobile number to continue.")
        wx.CallLater(160, self.login_panel.txt_phone.SetFocus)

    def clear_session(self):
        account = self._active_account()
        dlg = wx.MessageDialog(
            self,
            f"Clear local session data for '{account['label']}'?",
            "Clear Session",
            wx.YES_NO | wx.ICON_QUESTION
        )
        result = dlg.ShowModal()
        dlg.Destroy()
        if result != wx.ID_YES:
            return

        removed = self._delete_session_files_for_account(account)
        self.show_login()
        wx.MessageBox(f"Cleared {removed} local file(s) for '{account['label']}'.", "Session Cleared")

    def set_operation_mode(self, mode, message=None):
        self.current_mode = mode
        if hasattr(self, "status_bar") and self.status_bar:
            self.status_bar.SetStatusText(f"Mode: {mode}", 0)
            if message is not None:
                self.status_bar.SetStatusText(str(message), 1)

    def show_toast(self, title, message):
        if not wxadv:
            return
        try:
            n = wxadv.NotificationMessage(title=title, message=message, parent=self)
            n.Show(timeout=2)
        except Exception:
            pass

    def notify_user(self, message, is_error=False, toast=False):
        self.update_status(message, err=is_error)
        if toast:
            self.show_toast("Smart TG Downloader", message)

    def update_status(self, msg, err=False):
        self.last_status_message = str(msg)
        if self.login_panel.IsShown():
            self.login_panel.status.SetLabel(msg)
        else:
            self.lbl_status.SetLabel(msg)
        if err:
            AccessibilityHelper.announce_error(self, str(msg))
        else:
            AccessibilityHelper.announce(self, str(msg))
        self.set_operation_mode(self.current_mode, msg)

    def update_progress(self, cmd, data, extra=0):
        if cmd == "START_DIALOG":
            self.is_downloading = True
            if not self.progress_dlg:
                self.progress_dlg = DownloadProgressDialog(self)
                self.progress_dlg.Show()
            if self.progress_dlg:
                wx.CallAfter(self.progress_dlg.txt_log.SetFocus)
            if self.current_mode not in ("Analyzing", "Downloading"):
                self.set_operation_mode("Downloading", "Processing task")
        elif cmd == "SCAN_DONE":
            self.is_downloading = False
            if self.progress_dlg:
                self.progress_dlg.Destroy()
                self.progress_dlg = None
            self.set_operation_mode("Ready", "Analysis complete")
            self.show_scan_results(data)
        elif cmd == "FINISHED":
            self.is_downloading = False
            if self.progress_dlg:
                self.progress_dlg.Destroy()
                self.progress_dlg = None
            self.notify_user("Download completed.", toast=True)
            self.txt_link.SetValue("")
            self.txt_link.Show()
            self.lbl_status.SetLabel("Ready to start.")
            self.btn_open_folder.Show()
            self.set_operation_mode("Ready", "Download complete")
            self.download_panel.Layout()
            wx.CallLater(120, self.txt_link.SetFocus)
        elif cmd == "TASK_ABORTED":
            self.is_downloading = False
            if self.progress_dlg:
                self.progress_dlg.Destroy()
                self.progress_dlg = None
            self.lbl_status.SetLabel("Operation stopped.")
            self.set_operation_mode("Ready", "Operation stopped")
            self.notify_user(str(data), is_error=True, toast=True)
            wx.CallLater(120, self.txt_link.SetFocus)
        elif cmd == "PAUSED_STATE":
            if self.progress_dlg:
                self.progress_dlg.set_paused_state(bool(data))
            if data:
                self.set_operation_mode("Paused", "Downloads paused")
                self.lbl_status.SetLabel("Downloads paused.")
            else:
                self.set_operation_mode("Downloading", "Downloads resumed")
                self.lbl_status.SetLabel("Downloads resumed.")
        elif cmd == "LOG":
            if self.progress_dlg:
                self.progress_dlg.log_message(data)
        elif cmd == "PROGRESS":
            if self.progress_dlg:
                self.progress_dlg.update_progress(*data)

    def show_scan_results(self, summary):
        items = summary.get("items", [])
        if not items:
            self.lbl_status.SetLabel("Scan complete with no matching files.")
            self.notify_user("Scan complete with no matching files.")
            wx.CallLater(120, self.txt_link.SetFocus)
            return

        dlg = ScanResultsDialog(self, summary)
        result = dlg.ShowModal()
        selected_keys = dlg.get_selected_keys() if result == wx.ID_OK else []
        dlg.Destroy()

        if not selected_keys:
            self.lbl_status.SetLabel("Scan complete. Download cancelled.")
            self.notify_user("Scan complete. Download cancelled.")
            wx.CallLater(120, self.txt_link.SetFocus)
            return

        self.lbl_status.SetLabel(f"Starting download for {len(selected_keys)} selected files...")
        self.set_operation_mode("Downloading", f"Downloading {len(selected_keys)} files")
        self.worker._run_task(self.worker.download_pending_scan(selected_keys))

    def init_download_ui(self):
        p = self.download_panel
        v = wx.BoxSizer(wx.VERTICAL)
        t = wx.StaticText(p, label="TG Downloader"); f=t.GetFont(); f.PointSize+=4; f.Weight=wx.FONTWEIGHT_BOLD; t.SetFont(f)
        v.Add(t, 0, wx.ALL|wx.CENTER, 20)
        self.lbl_instr = wx.StaticText(p, label="Paste your Telegram Link below:")
        v.Add(self.lbl_instr, 0, wx.LEFT|wx.RIGHT|wx.TOP, 25)
        self.txt_link = wx.TextCtrl(p, value="")
        AccessibilityHelper.set_accessible_properties(
            self.txt_link,
            "Telegram link input",
            "Paste Telegram channel or message link"
        )
        AccessibilityHelper.bind_focus_announcement(self.txt_link, self, "Telegram link input")
        self.txt_link.SetHint("Paste Link Here (e.g. t.me/channel/123)")
        self.txt_link.Bind(wx.EVT_TEXT, self.on_text_change)
        v.Add(self.txt_link, 0, wx.ALL|wx.EXPAND, 20)
        
        hbox = wx.BoxSizer(wx.HORIZONTAL)
        self.btn_process = wx.Button(p, label="Analyze Link")
        AccessibilityHelper.set_accessible_properties(
            self.btn_process,
            "Analyze link button",
            "Analyze link, choose filters, and review scan results"
        )
        self.btn_process.Bind(wx.EVT_BUTTON, self.on_process)
        self.btn_process.Disable()
        self.btn_process.Hide()
        hbox.Add(self.btn_process, 1, wx.CENTER|wx.EXPAND, 0)
        
        v.Add(hbox, 0, wx.ALL|wx.EXPAND, 20)

        self.btn_open_folder = wx.Button(p, label="Open Download Folder")
        AccessibilityHelper.set_accessible_properties(
            self.btn_open_folder,
            "Open download folder button",
            "Open the Telegram download directory"
        )
        self.btn_open_folder.Bind(wx.EVT_BUTTON, self.on_open_download_folder)
        v.Add(self.btn_open_folder, 0, wx.ALL|wx.EXPAND, 20)
        self.btn_open_folder.Hide()

        self.lbl_status = wx.StaticText(p, label="Ready to start.")
        v.Add(self.lbl_status, 1, wx.ALL|wx.CENTER, 20)
        v.Add(wx.StaticText(p, label="Need help? Press F1."), 0, wx.ALL|wx.CENTER, 15)
        p.SetSizer(v); p.Hide()

    def on_open_download_folder(self, event):
        try:
            os.startfile(DOWNLOAD_ROOT)
        except Exception as e:
            self.notify_user(f"Could not open download folder: {e}", is_error=True)

    def on_text_change(self, e):
        has_link = bool(self.txt_link.GetValue().strip())
        self.btn_process.Show(has_link)
        self.btn_process.Enable(has_link)
        if not has_link:
            self.btn_open_folder.Hide()
        self.download_panel.Layout()

    def on_process(self, e):
        link = self.txt_link.GetValue().strip()
        if not link:
            AccessibilityHelper.announce_error(self, "Telegram link is required")
            self.notify_user("Telegram link is required.", is_error=True)
            return
        link_l = link.lower()
        if "t.me/" not in link_l and "telegram.me/" not in link_l and not link_l.startswith("tg://"):
            AccessibilityHelper.announce_error(self, "Please enter a valid Telegram link")
            self.notify_user("Please enter a valid Telegram link.", is_error=True)
            return

        filter_options = ["All", "Audio", "Video", "Images", "Docs"]
        d = wx.MultiChoiceDialog(
            self,
            "Select content types to include in scan results:",
            "Content Filters",
            filter_options
        )
        d.SetSelections([1, 2])
        if d.ShowModal() == wx.ID_OK:
            selections = [filter_options[i] for i in d.GetSelections()]
            if not selections:
                self.notify_user("Select at least one filter.", is_error=True)
                d.Destroy()
                return
            if "All" in selections:
                selections = ["All"]
            self.lbl_status.SetLabel("Analyzing link...")
            self.set_operation_mode("Analyzing", "Analyzing link")
            self.btn_open_folder.Hide()
            self.download_panel.Layout()
            self.worker._run_task(self.worker.scan_link(link, selections))
            wx.CallLater(120, self.txt_link.SetFocus)
        d.Destroy()

    async def check_update_task(self):
        res = await self.worker.check_for_updates()
        if res.get('update'):
            wx.CallAfter(self.show_update_prompt, res)

    def show_update_prompt(self, res):
        d = UpdateAvailableDialog(self, res['version'], res['whats_new'])
        ret = d.ShowModal()
        d.Destroy()
        if ret == wx.ID_YES:
            self.worker._run_task(self.worker.perform_update(res['download_url']))
        else:
            import sys
            sys.exit(0)

def run_app():
    app = wx.App()
    MainFrame()
    app.MainLoop()
