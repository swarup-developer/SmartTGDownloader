from .utils import *
from .utils import _clean_filename

# --- Telegram Worker ---
class TelegramWorker:
    def __init__(self, update_callback, progress_callback, login_success_callback):
        self.loop = asyncio.new_event_loop()
        self.client = None
        self.session_file = str(SESSION_FILE)
        self.update_callback = update_callback 
        self.progress_callback = progress_callback
        self.login_success_callback = login_success_callback
        self.stop_flag = False
        self.pause_flag = False
        self.pending_scan = None
        self.max_parallel_downloads = DOWNLOAD_BATCH_SIZE
        self.speed_limit_kbps = 0
        self.speed_limit_bps = 0
        self.semaphore = asyncio.Semaphore(self.max_parallel_downloads)
        self.apply_transfer_settings()
        self.thread = threading.Thread(target=self._start_loop, daemon=True)
        self.thread.start()

    def _start_loop(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def _run_task(self, coro):
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    def update_gui(self, message, is_error=False):
        wx.CallAfter(self.update_callback, message, is_error)

    def set_session_file(self, session_path):
        self.session_file = str(session_path)
        
    def stop_download(self):
        self.stop_flag = True
        self.pause_flag = False

    def pause_downloads(self):
        self.pause_flag = True
        wx.CallAfter(self.progress_callback, "PAUSED_STATE", True, 0)

    def resume_downloads(self):
        self.pause_flag = False
        wx.CallAfter(self.progress_callback, "PAUSED_STATE", False, 0)

    def apply_transfer_settings(self):
        cfg = load_settings()
        self.max_parallel_downloads = DOWNLOAD_BATCH_SIZE
        self.semaphore = asyncio.Semaphore(self.max_parallel_downloads)

        try:
            speed_kbps = int(cfg.get("speed_limit_kbps", 0))
        except Exception:
            speed_kbps = 0
        speed_kbps = max(0, speed_kbps)
        self.speed_limit_kbps = speed_kbps
        self.speed_limit_bps = speed_kbps * 1024

    async def _wait_if_paused(self):
        while self.pause_flag and not self.stop_flag:
            await asyncio.sleep(0.2)

    def get_proxy_args(self):
        p = load_settings()
        if not p.get('proxy_enabled', False): return None
        if not socks: return None
        ptype = socks.SOCKS5 if p['proxy_type'] == 'SOCKS5' else socks.HTTP
        return (ptype, p['proxy_host'], int(p['proxy_port']), True, p.get('proxy_user'), p.get('proxy_pass'))
    
    def _get_clean_filename_str(self, msg):
        return _clean_filename(msg)

    def _normalize_link(self, link):
        raw = link.strip()
        if not raw:
            raise ValueError("Telegram link is required.")
        if not raw.startswith(("http://", "https://", "tg://")):
            raw = "https://" + raw
        return raw

    @staticmethod
    def _safe_int(value):
        try:
            if value is None:
                return None
            text = str(value).strip()
            if not text:
                return None
            return int(text)
        except Exception:
            return None

    @staticmethod
    def _pick_first(query_map, key):
        values = query_map.get(key) or []
        if not values:
            return None
        return str(values[0]).strip()

    def _parse_tg_link(self, parsed):
        action = (parsed.netloc or parsed.path or "").strip("/").lower()
        query_map = parse_qs(parsed.query or "")

        if action == "resolve":
            domain = self._pick_first(query_map, "domain")
            if not domain:
                raise ValueError("Invalid tg://resolve link.")
            msg_id = self._safe_int(self._pick_first(query_map, "post"))
            return domain, msg_id

        if action == "join":
            invite_hash = self._pick_first(query_map, "invite")
            if not invite_hash:
                raise ValueError("Invalid tg://join link.")
            return {"invite_hash": invite_hash}, None

        if action == "privatepost":
            channel = self._pick_first(query_map, "channel")
            post = self._safe_int(self._pick_first(query_map, "post"))
            if not channel:
                raise ValueError("Invalid tg://privatepost link.")
            channel_id = str(channel).strip()
            if not channel_id.lstrip("-").isdigit():
                raise ValueError("Invalid tg://privatepost channel.")
            normalized_id = int(channel_id)
            if not str(normalized_id).startswith("-100"):
                normalized_id = int(f"-100{str(normalized_id).lstrip('-')}")
            return normalized_id, post

        raise ValueError("Unsupported tg:// link format.")

    def _parse_link(self, link):
        normalized = self._normalize_link(link)
        parsed = urlparse(normalized)

        if parsed.scheme == "tg":
            return self._parse_tg_link(parsed)

        host = parsed.netloc.lower()
        if host.startswith("www."):
            host = host[4:]
        if host not in ("t.me", "telegram.me"):
            raise ValueError("Only Telegram links from t.me are supported.")

        query_map = parse_qs(parsed.query or "")
        path = parsed.path.strip("/")
        parts = [unquote(p).strip() for p in path.split("/") if p.strip()]
        if not parts:
            raise ValueError("Incomplete Telegram link.")

        # Public view links use /s/<channel>/<msg_id>
        if parts and parts[0].lower() == "s":
            parts = parts[1:]
            if not parts:
                raise ValueError("Invalid public channel link.")

        if parts[0] == "c":
            if len(parts) < 2 or not parts[1].isdigit():
                raise ValueError("Invalid private channel link.")
            entity_id = int(f"-100{parts[1]}")
            msg_id = self._safe_int(parts[2] if len(parts) > 2 else None)
            if msg_id is None:
                msg_id = self._safe_int(self._pick_first(query_map, "post"))
            return entity_id, msg_id

        if parts[0].startswith("+"):
            invite_hash = parts[0][1:].strip()
            if not invite_hash:
                raise ValueError("Invalid invite link.")
            msg_id = self._safe_int(parts[1] if len(parts) > 1 else None)
            if msg_id is None:
                msg_id = self._safe_int(self._pick_first(query_map, "post"))
            return {"invite_hash": invite_hash}, msg_id

        if parts[0] == "joinchat":
            if len(parts) < 2 or not parts[1]:
                raise ValueError("Invalid joinchat link.")
            msg_id = self._safe_int(parts[2] if len(parts) > 2 else None)
            if msg_id is None:
                msg_id = self._safe_int(self._pick_first(query_map, "post"))
            return {"invite_hash": parts[1].strip()}, msg_id

        entity_ref = parts[0]
        msg_id = self._safe_int(parts[1] if len(parts) > 1 else None)
        if msg_id is None:
            msg_id = self._safe_int(self._pick_first(query_map, "post"))
        return entity_ref, msg_id

    async def _resolve_entity(self, entity_ref):
        if isinstance(entity_ref, dict):
            invite_hash = entity_ref.get("invite_hash")
            if not invite_hash:
                raise ValueError("Invalid invite link.")
            try:
                invite_info = await self.client(CheckChatInviteRequest(invite_hash))
                if isinstance(invite_info, types.ChatInviteAlready):
                    return await self.client.get_entity(invite_info.chat)
            except (errors.InviteHashExpiredError, errors.InviteHashInvalidError):
                raise ValueError("Invite link is invalid or expired.")
            except Exception:
                pass

            try:
                result = await self.client(ImportChatInviteRequest(invite_hash))
                chats = getattr(result, "chats", [])
                if chats:
                    return chats[0]
            except errors.UserAlreadyParticipantError:
                pass
            except (errors.InviteHashExpiredError, errors.InviteHashInvalidError):
                raise ValueError("Invite link is invalid or expired.")

            raise ValueError("Unable to open this private invite link.")

        try:
            return await self.client.get_entity(entity_ref)
        except (errors.UsernameInvalidError, errors.UsernameNotOccupiedError):
            raise ValueError("Telegram entity was not found. Check the link and try again.")
        except ValueError:
            raise ValueError("Unable to resolve this Telegram link with the current account.")

    def _entity_title(self, entity):
        return (
            getattr(entity, "title", None)
            or getattr(entity, "username", None)
            or getattr(entity, "first_name", None)
            or "TelegramContent"
        )

    @staticmethod
    def _message_document(msg):
        doc = getattr(msg, "document", None)
        if doc:
            return doc
        media = getattr(msg, "media", None)
        webpage = getattr(media, "webpage", None) if media else None
        return getattr(webpage, "document", None)

    @staticmethod
    def _message_photo(msg):
        photo = getattr(msg, "photo", None)
        if photo:
            return photo
        media = getattr(msg, "media", None)
        webpage = getattr(media, "webpage", None) if media else None
        return getattr(webpage, "photo", None)

    def _message_mime_type(self, msg):
        msg_file = getattr(msg, "file", None)
        mime = getattr(msg_file, "mime_type", None) if msg_file else None
        if mime:
            return str(mime).lower()
        doc = self._message_document(msg)
        doc_mime = getattr(doc, "mime_type", None) if doc else None
        return str(doc_mime).lower() if doc_mime else ""

    def _message_size(self, msg):
        msg_file = getattr(msg, "file", None)
        size = getattr(msg_file, "size", None) if msg_file else None
        if size is not None:
            return int(size or 0)
        doc = self._message_document(msg)
        if doc and getattr(doc, "size", None) is not None:
            return int(doc.size or 0)
        return 0

    def _message_download_target(self, msg):
        if not msg:
            return None
        # Passing the whole message gives Telethon the best chance to resolve
        # media types including webpage documents and bot-delivered files.
        has_file_wrapper = getattr(msg, "file", None) is not None
        if has_file_wrapper or self._message_document(msg) or self._message_photo(msg):
            return msg
        return None

    def _message_has_downloadable_media(self, msg):
        return self._message_download_target(msg) is not None

    def _category_for_message(self, msg):
        if not msg or not self._message_has_downloadable_media(msg):
            return None

        if getattr(msg, "audio", None) or getattr(msg, "voice", None):
            return "Audio"
        if getattr(msg, "video", None) or getattr(msg, "video_note", None):
            return "Video"
        if self._message_photo(msg):
            return "Images"

        mime = self._message_mime_type(msg)
        if mime.startswith("audio"):
            return "Audio"
        if mime.startswith("video"):
            return "Video"
        if mime.startswith("image"):
            return "Images"
        return "Docs"

    def _matches_filter(self, category, filters):
        if not category:
            return False
        if not filters or "All" in filters:
            return True
        return category in filters

    @staticmethod
    def _next_available_path(path):
        if not path.exists():
            return path
        stem = path.stem
        suffix = path.suffix
        idx = 2
        while True:
            candidate = path.with_name(f"{stem} ({idx}){suffix}")
            if not candidate.exists():
                return candidate
            idx += 1

    def _build_download_path(self, folder, file_name, msg_id):
        stem, ext = os.path.splitext(file_name)
        suffix = f"_m{msg_id}" if msg_id is not None else ""
        if suffix and not stem.lower().endswith(suffix.lower()):
            file_name = f"{stem}{suffix}{ext}"
        return folder / file_name

    def _natural_sort_key(self, msg):
        fname = self._get_clean_filename_str(msg)
        return [int(text) if text.isdigit() else text.lower() for text in re.split("([0-9]+)", fname)]

    @staticmethod
    def _should_emit_progress(last_ts, now_ts, last_percent, percent, downloaded, total_size):
        if percent >= 100:
            return True
        if total_size > 0 and downloaded >= total_size:
            return True
        if percent != last_percent and (percent % 2 == 0):
            return True
        if (now_ts - last_ts) >= 0.25:
            return True
        return False

    async def connect_and_send_code(self, api_id, api_hash, phone):
        try:
            proxy = self.get_proxy_args()
            self.client = TelegramClient(self.session_file, api_id, api_hash, proxy=proxy, **DEVICE_INFO)
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

    async def send_code_only(self, phone):
        try:
            if not self.client:
                return "ERROR"
            await self.client.send_code_request(phone)
            return "CODE_SENT"
        except Exception:
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
            self.client = TelegramClient(self.session_file, api_id, api_hash, proxy=proxy, **DEVICE_INFO)
            await self.client.connect()
            return True
        except: return False

    async def disconnect_client(self):
        try:
            if self.client:
                await self.client.disconnect()
            return True
        except Exception:
            return False

    async def logout_client(self):
        try:
            if self.client:
                if await self.client.is_user_authorized():
                    await self.client.log_out()
                await self.client.disconnect()
            return True
        except Exception:
            return False

    async def check_for_updates(self):
        try:
            import urllib.request
            import json
            import ssl
            import re
            
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            
            url = "https://api.github.com/repos/swarup-developer/SmartTGDownloader/releases/latest"
            req = urllib.request.Request(url, headers={'User-Agent': 'smart-tg-downloader-updater'})
            loop = asyncio.get_event_loop()
            
            def fetch():
                with urllib.request.urlopen(req, context=ctx, timeout=8) as response:
                    return response.read().decode('utf-8')
            
            body = await loop.run_in_executor(None, fetch)
            data = json.loads(body)
            
            tag_name = data.get('tag_name', '')
            whats_new = data.get('body', 'No release notes available.')
            assets = data.get('assets', [])
            
            match = re.match(r'^v(\d+\.\d+\.\d+)-windows$', tag_name)
            if match:
                latest_ver = match.group(1)
                from .utils import CURRENT_VERSION
                if self.is_newer_version(latest_ver, CURRENT_VERSION):
                    download_url = ""
                    for asset in assets:
                        if asset.get('name', '').endswith('.exe'):
                            download_url = asset.get('browser_download_url', '')
                            break
                    if download_url:
                        return {
                            "update": True,
                            "version": latest_ver,
                            "whats_new": whats_new,
                            "download_url": download_url
                        }
        except Exception:
            pass
        return {"update": False}

    def is_newer_version(self, latest, current):
        try:
            l = [int(x) for x in latest.split('.')]
            c = [int(x) for x in current.split('.')]
            for i in range(3):
                if l[i] > c[i]: return True
                if l[i] < c[i]: return False
        except:
            pass
        return False

    async def perform_update(self, download_url):
        try:
            import urllib.request
            import ssl
            import sys
            import subprocess
            from pathlib import Path
            
            self.update_gui("Downloading Update... 0%")
            
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            
            req = urllib.request.Request(download_url, headers={'User-Agent': 'smart-tg-downloader-updater'})
            loop = asyncio.get_event_loop()
            
            def download_file():
                temp_exe = Path(sys.executable).parent / "SmartTGDownloader_new.exe"
                with urllib.request.urlopen(req, context=ctx, timeout=15) as response:
                    total_size = int(response.headers.get('content-length', 0))
                    downloaded = 0
                    block_size = 1024 * 64
                    with open(temp_exe, 'wb') as f:
                        while True:
                            block = response.read(block_size)
                            if not block:
                                break
                            f.write(block)
                            downloaded += len(block)
                            if total_size > 0:
                                percent = int(downloaded * 100 / total_size)
                                wx.CallAfter(self.update_gui, f"Downloading Update... {percent}%")
                return temp_exe

            temp_exe = await loop.run_in_executor(None, download_file)
            self.update_gui("Update Downloaded. Restarting...")
            
            current_exe = sys.executable
            batch_path = Path(current_exe).parent / "update_install.bat"
            
            batch_content = f"""@echo off
timeout /t 2 /nobreak > nul
move /y "{temp_exe}" "{current_exe}"
start "" "{current_exe}"
del "%~f0"
"""
            with open(batch_path, "w") as f:
                f.write(batch_content)
                
            subprocess.Popen([str(batch_path)], shell=True)
            wx.CallAfter(wx.GetApp().Exit)
            return True
        except Exception as e:
            self.update_gui(f"Update failed: {e}", True)
            return False

    async def analyze_link(self, link, filter_types):
        await self.scan_link(link, filter_types)

    async def scan_link(self, link, filter_types):
        self.stop_flag = False
        self.pause_flag = False

        try:
            if not self.client:
                raise RuntimeError("Telegram client is not connected. Please log in first.")
            if not await self.client.is_user_authorized():
                raise RuntimeError("Session expired. Please log in again.")

            self.apply_transfer_settings()
            wx.CallAfter(self.progress_callback, "START_DIALOG", 0, 0)
            wx.CallAfter(self.progress_callback, "LOG", "Starting link analysis...", 0)

            entity_ref, msg_id = self._parse_link(link)
            entity = await self._resolve_entity(entity_ref)
            title = self._entity_title(entity)
            filters = filter_types or ["Audio", "Video"]
            source_counts = {"Audio": 0, "Video": 0, "Images": 0, "Docs": 0}
            matched_counts = {"Audio": 0, "Video": 0, "Images": 0, "Docs": 0}
            matched_items = []

            wx.CallAfter(self.progress_callback, "LOG", f"Scanning source: {title}", 0)

            if msg_id:
                msg = await self.client.get_messages(entity, ids=msg_id)
                if isinstance(msg, list):
                    msg = msg[0] if msg else None
                if not msg:
                    raise ValueError(f"Message {msg_id} was not found or is not accessible.")
                category = self._category_for_message(msg)
                if category:
                    source_counts[category] += 1
                if self._matches_filter(category, filters):
                    size = self._message_size(msg)
                    key = f"{msg.id}:{size}:{category}:{len(matched_items)}"
                    matched_counts[category] += 1
                    matched_items.append(
                        {
                            "key": key,
                            "msg": msg,
                            "name": self._get_clean_filename_str(msg),
                            "size": size,
                            "type": category,
                            "date": str(getattr(msg, "date", ""))[:19]
                        }
                    )
            else:
                scanned = 0
                async for msg in self.client.iter_messages(entity):
                    if self.stop_flag:
                        break
                    scanned += 1

                    category = self._category_for_message(msg)
                    if not category:
                        continue

                    source_counts[category] += 1
                    if self._matches_filter(category, filters):
                        size = self._message_size(msg)
                        key = f"{msg.id}:{size}:{category}:{len(matched_items)}"
                        matched_counts[category] += 1
                        matched_items.append(
                            {
                                "key": key,
                                "msg": msg,
                                "name": self._get_clean_filename_str(msg),
                                "size": size,
                                "type": category,
                                "date": str(getattr(msg, "date", ""))[:19]
                            }
                        )

                    if scanned % 200 == 0:
                        wx.CallAfter(self.progress_callback, "LOG", f"Scanned {scanned} messages...", 0)
                    if scanned % 500 == 0:
                        # Yield periodically to keep the event loop responsive and CPU spikes lower.
                        await asyncio.sleep(0)

            if self.stop_flag:
                wx.CallAfter(self.progress_callback, "TASK_ABORTED", "Scan stopped.", 0)
                return

            matched_items.sort(key=lambda item: self._natural_sort_key(item["msg"]))
            total_size = sum(item["size"] for item in matched_items)
            estimate_bps = self.speed_limit_bps if self.speed_limit_bps > 0 else 2 * 1024 * 1024
            estimate_seconds = int(total_size / estimate_bps) if estimate_bps > 0 else 0
            selected_keys = [item["key"] for item in matched_items]

            self.pending_scan = {
                "title": title,
                "items": matched_items,
                "counts": matched_counts,
                "source_counts": source_counts,
                "filters": filters,
                "total_size": total_size,
                "estimated_seconds": estimate_seconds,
                "selected_keys": selected_keys
            }

            summary = {
                "title": title,
                "filters": filters,
                "count": len(matched_items),
                "counts": matched_counts,
                "source_counts": source_counts,
                "total_size": total_size,
                "estimated_seconds": estimate_seconds,
                "items": [
                    {
                        "key": item["key"],
                        "name": item["name"],
                        "size": item["size"],
                        "type": item["type"],
                        "date": item["date"]
                    }
                    for item in matched_items
                ]
            }
            wx.CallAfter(self.progress_callback, "SCAN_DONE", summary, 0)

        except Exception as e:
            wx.CallAfter(self.progress_callback, "TASK_ABORTED", f"Error: {e}", 0)

    async def download_pending_scan(self, selected_keys=None):
        if not self.pending_scan or not self.pending_scan.get("items"):
            wx.CallAfter(self.progress_callback, "TASK_ABORTED", "No scanned files available to download.", 0)
            return

        self.stop_flag = False
        self.pause_flag = False
        self.apply_transfer_settings()
        title = self.pending_scan["title"]
        items = self.pending_scan["items"]

        if selected_keys is not None:
            selected_set = set(selected_keys)
        else:
            selected_set = set(self.pending_scan.get("selected_keys", []))
            if not selected_set:
                selected_set = {item["key"] for item in items}

        selected_items = [item for item in items if item["key"] in selected_set]
        messages = [item["msg"] for item in selected_items]
        total_size = sum(item["size"] for item in selected_items)

        try:
            wx.CallAfter(self.progress_callback, "START_DIALOG", 0, 0)
            wx.CallAfter(
                self.progress_callback,
                "LOG",
                f"Downloading {len(messages)} selected files ({total_size} bytes) from {title}...",
                0
            )
            results = []
            total_batches = (len(messages) + self.max_parallel_downloads - 1) // self.max_parallel_downloads
            for batch_idx in range(total_batches):
                if self.stop_flag:
                    break
                start = batch_idx * self.max_parallel_downloads
                end = start + self.max_parallel_downloads
                batch = messages[start:end]
                wx.CallAfter(
                    self.progress_callback,
                    "LOG",
                    f"Starting batch {batch_idx + 1}/{total_batches} ({len(batch)} files)...",
                    0
                )
                batch_tasks = [self._download_item(msg, title) for msg in batch]
                batch_results = await asyncio.gather(*batch_tasks)
                results.extend(batch_results)
            if self.stop_flag:
                downloaded = sum(1 for ok in results if ok)
                wx.CallAfter(
                    self.progress_callback,
                    "TASK_ABORTED",
                    f"Download stopped. Downloaded {downloaded}/{len(messages)} files.",
                    0
                )
                return
            failed = sum(1 for ok in results if not ok)
            if failed:
                wx.CallAfter(
                    self.progress_callback,
                    "TASK_ABORTED",
                    f"Downloaded {len(messages) - failed}/{len(messages)} files. {failed} failed.",
                    0
                )
            else:
                wx.CallAfter(self.progress_callback, "FINISHED", 0, 0)
        except Exception as e:
            wx.CallAfter(self.progress_callback, "TASK_ABORTED", f"Download failed: {e}", 0)
        finally:
            self.pending_scan = None
        
    async def _download_item(self, msg, title):
        async with self.semaphore:
            if self.stop_flag:
                return False
            media_target = self._message_download_target(msg)
            if not msg or not media_target:
                return False
            await self._wait_if_paused()

            clean_name = self._get_clean_filename_str(msg)
            clean_chan = re.sub(r'[<>:"/\\|?*]', '', title).strip()
            
            mime = self._message_mime_type(msg)
            subfolder = "Docs"
            if mime.startswith("audio"): subfolder = "Audio"
            elif mime.startswith("video"): subfolder = "Video"
            elif mime.startswith("image"): subfolder = "Images"
            
            folder = DOWNLOAD_ROOT / clean_chan / subfolder
            folder.mkdir(parents=True, exist_ok=True)
            path = self._build_download_path(folder, clean_name, getattr(msg, "id", None))
            
            total_size = self._message_size(msg)
            download_offset = 0
            initial_offset = 0
            
            if path.exists():
                local_size = path.stat().st_size
                if total_size > 0 and local_size == total_size:
                    wx.CallAfter(self.progress_callback, "LOG", f"Skipping (Exists): {path.name}", 0)
                    return True
                elif total_size > 0 and local_size < total_size:
                    download_offset = local_size
                    initial_offset = local_size
                    wx.CallAfter(self.progress_callback, "LOG", f"Resuming: {path.name} ({local_size} bytes)", 0)
                elif total_size <= 0:
                    path = self._next_available_path(path)
                else:
                    path.unlink()
            
            wx.CallAfter(self.progress_callback, "LOG", f"Downloading: {path.name}", 0)

            try:
                file_start_time = time.monotonic()
                per_stream_limit = 0
                if self.speed_limit_bps > 0:
                    per_stream_limit = max(1, self.speed_limit_bps // max(1, self.max_parallel_downloads))
                streamed_any_data = False
                last_progress_ts = 0.0
                last_percent = -1

                with open(path, 'ab') as f:
                    async for chunk in self.client.iter_download(
                        media_target,
                        offset=download_offset,
                        request_size=1024 * 1024
                    ):
                        await self._wait_if_paused()
                        if self.stop_flag: 
                            wx.CallAfter(self.progress_callback, "LOG", "Stopped by User.", 0)
                            raise asyncio.CancelledError("Download stopped by user")
                        f.write(chunk)
                        streamed_any_data = True
                        download_offset += len(chunk)

                        if per_stream_limit > 0:
                            transferred = max(0, download_offset - initial_offset)
                            elapsed = max(0.001, time.monotonic() - file_start_time)
                            expected = transferred / per_stream_limit
                            if expected > elapsed:
                                await asyncio.sleep(expected - elapsed)
                        
                        percent = int((download_offset / total_size) * 100) if total_size > 0 else 0
                        now_ts = time.monotonic()
                        if self._should_emit_progress(
                            last_progress_ts,
                            now_ts,
                            last_percent,
                            percent,
                            download_offset,
                            total_size
                        ):
                            last_progress_ts = now_ts
                            last_percent = percent
                            wx.CallAfter(
                                self.progress_callback,
                                "PROGRESS",
                                (path.name, percent, download_offset, total_size),
                                0
                            )

                if total_size <= 0:
                    total_size = max(download_offset, 1)
                    wx.CallAfter(self.progress_callback, "PROGRESS", (path.name, 100, download_offset, total_size), 0)

                if not streamed_any_data and total_size > 0:
                    raise RuntimeError("No data stream received.")

                wx.CallAfter(self.progress_callback, "LOG", f"Completed: {path.name}", 0)
                return True
            except asyncio.CancelledError:
                wx.CallAfter(self.progress_callback, "LOG", f"Cancelled: {path.name}", 0)
                return False
            except Exception as e:
                try:
                    if download_offset == 0:
                        output = await self.client.download_media(media_target, file=str(path))
                        if output:
                            final_size = path.stat().st_size if path.exists() else 0
                            reported_total = total_size if total_size > 0 else max(final_size, 1)
                            wx.CallAfter(
                                self.progress_callback,
                                "PROGRESS",
                                (path.name, 100, final_size, reported_total),
                                0
                            )
                            wx.CallAfter(self.progress_callback, "LOG", f"Completed: {path.name}", 0)
                            return True
                except Exception:
                    pass
                wx.CallAfter(self.progress_callback, "LOG", f"Error downloading {path.name}: {e}", 0)
                return False

