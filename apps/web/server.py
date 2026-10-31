#!/usr/bin/env python3
"""Sonviaf local library server.

Serves the product page (apps/web) plus a read-only view of a local AI music
library:

    GET /api/library              -> works with format/duration metadata
    GET /api/waveform?rel=&buckets= -> real peak data for one file
    GET /media?rel=               -> original file, HTTP Range enabled

Files are never written, moved or re-encoded. Unsupported formats are reported
as not playable instead of being guessed.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.environ.get("SONVIAF_LIBRARY", r"E:\AI WAV")
CACHE_PATH = os.path.join(BASE, ".library-cache.json")
AUDIO_EXT = {".wav", ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".aiff", ".aif", ".wave"}
STATIC_EXT = {".htm", ".html", ".css", ".js", ".png", ".jpg", ".jpeg", ".svg", ".ico", ".webp", ".woff2"}
WEB_EXT = {".htm", ".html", ".css", ".js", ".png", ".jpg", ".jpeg", ".svg", ".ico", ".webp"}
PLAYABLE_TAGS = {1, 3}  # PCM, IEEE float
MAX_BUCKETS = 512

# A curated shelf: 20 finished songs rather than 4,000+ takes, stems and copies.
# Missing files are filled from the library automatically so the shelf is stable.
CURATED = [
    "自己的神话.wav",
    "那句再见.wav",
    "《无法到达》.wav",
    "句点15.wav",
    "《山风知道》.wav",
    "City of Echoes.wav",
    "我曾遥望漫天星光.wav",
    "沈知行 - Je ne veux pas enfermer ton aujourd'hui.wav",
    "《路过大理》.wav",
    "Même ainsi, tu restes dans l'amour.wav",
    "《北极星与黑夜》.wav",
    "《她的日程表里没有我》.wav",
    "Where the Wind Begins.wav",
    "Pour le moi pas encore écrit.wav",
    "《少年不散场》.wav",
    "vieillir-et-devenir-nouveau-avec-toi.wav",
    "Echoes of Tomorrow.wav",
    "Où es-tu maintenant.wav",
    "一念轻安.wav",
    "T'aimer lentement.wav",
]
SHELF_SIZE = 20
SKIP_MARKS = ("__dup", "split", "人声", "伴奏", "master", "mixing", "take", "_48k", "48k24", "bandicam")

try:
    import numpy as np
except Exception:  # pragma: no cover - numpy is expected on this machine
    np = None


# --------------------------------------------------------------------------
# WAV parsing
# --------------------------------------------------------------------------
def parse_wav(path: str):
    """Read the RIFF/WAVE header only. Returns metadata dict or None."""
    try:
        total = os.path.getsize(path)
        with open(path, "rb") as handle:
            head = handle.read(12)
            if len(head) < 12:
                return None
            chunk_id, _size, form = struct.unpack("<4sI4s", head)
            if chunk_id not in (b"RIFF", b"RF64") or form != b"WAVE":
                return None

            fmt = None
            ds64_data = None
            data_off = None
            data_len = None
            while True:
                chunk = handle.read(8)
                if len(chunk) < 8:
                    break
                cid, csize = struct.unpack("<4sI", chunk)
                if cid == b"fmt ":
                    raw = handle.read(csize)
                    if len(raw) < 16:
                        return None
                    tag, ch, sr, byte_rate, block_align, bits = struct.unpack("<HHIIHH", raw[:16])
                    if tag == 0xFFFE and len(raw) >= 40:
                        sub = struct.unpack("<H", raw[24:26])[0]
                        tag = sub if sub in (1, 3) else tag
                    fmt = {
                        "tag": tag,
                        "ch": ch,
                        "sr": sr,
                        "byterate": byte_rate,
                        "blockalign": block_align,
                        "bits": bits,
                    }
                    if csize % 2:
                        handle.read(1)
                elif cid == b"ds64":
                    raw = handle.read(csize)
                    if len(raw) >= 24:
                        _, ds64_data, _ = struct.unpack("<QQQ", raw[:24])
                    if csize % 2:
                        handle.read(1)
                elif cid == b"data":
                    data_off = handle.tell()
                    data_len = csize
                    if data_len == 0xFFFFFFFF and ds64_data:
                        data_len = ds64_data
                    break
                else:
                    handle.seek(csize + (csize % 2), 1)

            if not fmt or data_off is None or not data_len:
                return None

            block_align = fmt["blockalign"] or (fmt["ch"] * max(1, (fmt["bits"] + 7) // 8))
            if not block_align or not fmt["sr"]:
                return None
            frames = data_len // block_align
            duration = frames / float(fmt["sr"])
            playable = fmt["tag"] in PLAYABLE_TAGS and fmt["bits"] in (8, 16, 24, 32, 64)

            return {
                "tag": fmt["tag"],
                "ch": fmt["ch"],
                "sr": fmt["sr"],
                "bits": fmt["bits"],
                "frames": frames,
                "dur": round(duration, 2),
                "data_off": data_off,
                "data_len": data_len,
                "blockalign": block_align,
                "playable": playable,
            }
    except Exception:
        return None


def compute_peaks(path: str, info: dict, buckets: int):
    """Peak envelope from real samples. Returns list of 0..100 ints or None."""
    if np is None or not info.get("playable"):
        return None
    bits = info["bits"]
    tag = info["tag"]
    ch = info["ch"]
    frames = info["frames"]
    if frames <= 0:
        return None

    if tag == 1:
        if bits == 8:
            dtype, scale, signed = np.uint8, 128.0, False
        elif bits == 16:
            dtype, scale, signed = np.int16, float(1 << 15), True
        elif bits == 32:
            dtype, scale, signed = np.int32, float(1 << 31), True
        elif bits == 24:
            dtype, scale, signed = None, float(1 << 23), True
        else:
            return None
    elif tag == 3 and bits in (32, 64):
        dtype = np.float32 if bits == 32 else np.float64
        scale, signed = 1.0, True
        if dtype is np.float64:
            dtype = np.float32
    else:
        return None

    try:
        if dtype is None:  # 24-bit packed PCM
            raw = np.memmap(path, dtype=np.uint8, mode="r", offset=info["data_off"], shape=(frames, ch * 3))
        else:
            raw = np.memmap(path, dtype=dtype, mode="r", offset=info["data_off"], shape=(frames, ch))

        step = max(1, frames // buckets)
        window = max(32, min(2048, step))
        peaks = []
        for index in range(buckets):
            start = index * step
            if start >= frames:
                break
            block = raw[start : start + window]
            if block.size == 0:
                break
            if tag == 3:
                peak = float(np.max(np.abs(block.astype(np.float32))))
            elif dtype is None:
                triple = block.astype(np.int32).reshape(-1, 3)
                values = triple[:, 0] | (triple[:, 1] << 8) | (triple[:, 2] << 16)
                values = np.where(values >= (1 << 23), values - (1 << 24), values)
                peak = float(np.max(np.abs(values)))
            elif not signed:
                peak = float(np.max(np.abs(block.astype(np.int16) - 128)))
            else:
                peak = float(np.max(np.abs(block.astype(np.int32))))
            peaks.append(max(0, min(100, int(round(peak / scale * 100)))))
        return peaks
    except Exception:
        return None
    finally:
        try:
            del raw
        except Exception:
            pass


def fnv_id(rel_path: str) -> str:
    value = 2166136261
    for byte in rel_path.encode("utf-8", "ignore"):
        value ^= byte
        value = (value * 16777619) & 0xFFFFFFFF
    alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    out = ""
    for _ in range(5):
        out = alphabet[value % 36] + out
        value //= 36
    return f"SVF-{out}"


def display_name(filename: str) -> str:
    stem = os.path.splitext(filename)[0]
    return stem.strip() or filename


# --------------------------------------------------------------------------
# Library index
# --------------------------------------------------------------------------
class Library:
    def __init__(self, root: str):
        self.root = root
        self.works = []
        self.total = 0
        self.ready = threading.Event()
        self.scanned_at = 0.0
        self.lock = threading.Lock()
        self.peak_cache = {}
        self.peak_lock = threading.Lock()
        self.error = None

    def start(self):
        threading.Thread(target=self._scan, daemon=True).start()

    def _load_cache(self):
        try:
            with open(CACHE_PATH, "r", encoding="utf-8") as handle:
                data = json.load(handle)
            if data.get("root") == self.root:
                return {item["rel"]: item for item in data.get("items", [])}
        except Exception:
            pass
        return {}

    def _scan(self):
        started = time.time()
        cached = self._load_cache()
        items = []
        skipped = 0
        for dirpath, dirnames, filenames in os.walk(self.root):
            dirnames[:] = [d for d in dirnames if not d.startswith(".")]
            for filename in filenames:
                if os.path.splitext(filename)[1].lower() not in AUDIO_EXT:
                    continue
                full = os.path.join(dirpath, filename)
                rel = os.path.relpath(full, self.root).replace("\\", "/")
                try:
                    stat = os.stat(full)
                except OSError:
                    skipped += 1
                    continue

                info = cached.get(rel)
                if not info or info.get("size") != stat.st_size or abs(info.get("mtime", 0) - stat.st_mtime) > 1:
                    parsed = parse_wav(full) if filename.lower().endswith((".wav", ".wave")) else None
                    info = {
                        "rel": rel,
                        "size": stat.st_size,
                        "mtime": stat.st_mtime,
                        "dur": parsed["dur"] if parsed else None,
                        "sr": parsed["sr"] if parsed else None,
                        "bits": parsed["bits"] if parsed else None,
                        "ch": parsed["ch"] if parsed else None,
                        "playable": bool(parsed and parsed["playable"]),
                    }

                items.append(
                    {
                        "id": fnv_id(rel),
                        "name": display_name(filename),
                        "file": filename,
                        "rel": rel,
                        "size": stat.st_size,
                        "mtime": stat.st_mtime,
                        "dur": info.get("dur"),
                        "sr": info.get("sr"),
                        "bits": info.get("bits"),
                        "ch": info.get("ch"),
                        "playable": info.get("playable", False),
                    }
                )

        items.sort(key=lambda item: (-item["mtime"], item["name"].lower()))
        shelf = self._curate(items)
        with self.lock:
            self.works = shelf
            self.total = len(items)
            self.scanned_at = time.time()
        self.ready.set()
        try:
            with open(CACHE_PATH, "w", encoding="utf-8") as handle:
                json.dump({"root": self.root, "items": items}, handle, ensure_ascii=False)
        except Exception:
            pass
        print(f"[library] shelf {len(shelf)} / {len(items)} works in {time.time() - started:.1f}s ({skipped} skipped)")

    def _curate(self, items):
        by_name = {}
        for item in items:
            by_name.setdefault(item["file"], item)
        picked = []
        seen = set()
        for name in CURATED:
            item = by_name.get(name)
            if item and name not in seen:
                picked.append(item)
                seen.add(name)
        if len(picked) < SHELF_SIZE:
            for item in items:
                if len(picked) >= SHELF_SIZE:
                    break
                name = item["file"]
                if name in seen or not item["playable"]:
                    continue
                if not (120 <= (item["dur"] or 0) <= 330):
                    continue
                lowered = name.lower()
                if any(mark in lowered for mark in SKIP_MARKS):
                    continue
                picked.append(item)
                seen.add(name)
        return picked

    def wait(self, timeout=45.0):
        self.ready.wait(timeout)
        return self.ready.is_set()

    def payload(self):
        with self.lock:
            works = list(self.works)
            total = self.total
        return {
            "root": self.root,
            "count": len(works),
            "total": total,
            "generatedAt": int(self.scanned_at),
            "works": works,
        }

    def peaks(self, rel: str, buckets: int):
        full = self.resolve(rel)
        if not full:
            return None
        parsed = parse_wav(full)
        if not parsed:
            return None
        key = (rel, parsed["data_len"], parsed["frames"], buckets)
        with self.peak_lock:
            if key in self.peak_cache:
                return self.peak_cache[key]
        peaks = compute_peaks(full, parsed, buckets)
        if peaks is None:
            return None
        result = {"peaks": peaks, "dur": parsed["dur"], "sr": parsed["sr"], "bits": parsed["bits"], "ch": parsed["ch"]}
        with self.peak_lock:
            if len(self.peak_cache) > 12:
                self.peak_cache.clear()
            self.peak_cache[key] = result
        return result

    def resolve(self, rel: str):
        if not rel:
            return None
        candidate = os.path.realpath(os.path.join(self.root, rel.replace("\\", os.sep)))
        root = os.path.realpath(self.root)
        if not candidate.startswith(root + os.sep):
            return None
        return candidate if os.path.isfile(candidate) else None


LIB = Library(DEFAULT_ROOT)


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------
class Handler(SimpleHTTPRequestHandler):
    server_version = "SonviafLibrary/0.1"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=BASE, **kwargs)

    def log_message(self, fmt, *args):
        if args and "/api/" in str(args[0]):
            return
        super().log_message(fmt, *args)

    def end_headers(self):
        path = urlparse(self.path).path
        if path.endswith((".htm", ".html", ".css", ".js")):
            self.send_header("Cache-Control", "no-cache, must-revalidate")
        super().end_headers()

    def do_HEAD(self):
        if self._is_api():
            self._json({"ok": True})
            return
        super().do_HEAD()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/library":
            self._api_library()
            return
        if parsed.path == "/api/waveform":
            self._api_waveform(parsed)
            return
        if parsed.path.startswith("/media/"):
            self._media(unquote(parsed.path[len("/media/"):]))
            return
        if parsed.path == "/media":
            self._media(unquote(parse_qs(parsed.query).get("rel", [""])[0]))
            return
        if parsed.path in ("/", "/index.htm", "/index.html"):
            self.path = "/index.htm"
        super().do_GET()

    def _is_api(self):
        return urlparse(self.path).path.startswith("/api/")

    def _json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _api_library(self):
        if not LIB.wait():
            self._json({"error": "library scan in progress"}, 503)
            return
        self._json(LIB.payload())

    def _api_waveform(self, parsed):
        query = parse_qs(parsed.query)
        rel = unquote(query.get("rel", [""])[0])
        try:
            buckets = int(query.get("buckets", ["220"])[0])
        except ValueError:
            buckets = 220
        buckets = max(32, min(MAX_BUCKETS, buckets))
        if not LIB.wait():
            self._json({"error": "library scan in progress"}, 503)
            return
        if not LIB.resolve(rel):
            self._json({"error": "not found"}, 404)
            return
        peaks = LIB.peaks(rel, buckets)
        if peaks is None:
            self._json({"error": "waveform unavailable for this format"}, 415)
            return
        self._json(peaks)

    def _media(self, rel):
        full = LIB.resolve(rel)
        if not full:
            self.send_error(404, "not found")
            return
        size = os.path.getsize(full)
        content_type = self.guess_type(full)
        range_header = self.headers.get("Range")
        start, end = 0, size - 1
        status = 200
        if range_header and range_header.startswith("bytes="):
            spec = range_header.split("=", 1)[1].split(",")[0].strip()
            first, _, last = spec.partition("-")
            try:
                if first:
                    start = int(first)
                    end = int(last) if last else size - 1
                elif last:
                    start = max(0, size - int(last))
                end = min(end, size - 1)
                if start <= end:
                    status = 206
            except ValueError:
                start, end, status = 0, size - 1, 200
        length = end - start + 1

        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(full, "rb") as handle:
            handle.seek(start)
            remaining = length
            while remaining > 0:
                chunk = handle.read(min(262144, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)

    def do_POST(self):
        self.send_error(405, "read only")

    def list_directory(self, path):
        self.send_error(404, "not found")
        return None

    def translate_path(self, path):
        resolved = super().translate_path(path)
        if not resolved.startswith(BASE):
            return os.path.join(BASE, "__blocked__")
        ext = os.path.splitext(resolved)[1].lower()
        if ext and ext not in WEB_EXT:
            return os.path.join(BASE, "__blocked__")
        return resolved

    def guess_type(self, path):
        ext = os.path.splitext(path)[1].lower()
        return {
            ".wav": "audio/wav",
            ".wave": "audio/wav",
            ".mp3": "audio/mpeg",
            ".flac": "audio/flac",
            ".m4a": "audio/mp4",
            ".aac": "audio/aac",
            ".ogg": "audio/ogg",
            ".aiff": "audio/aiff",
            ".aif": "audio/aiff",
        }.get(ext, super().guess_type(path))


def main():
    global LIB
    parser = argparse.ArgumentParser(description="Sonviaf local library server")
    parser.add_argument("--root", default=DEFAULT_ROOT, help="music library folder")
    parser.add_argument("--port", type=int, default=4173)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    if not os.path.isdir(args.root):
        raise SystemExit(f"library root not found: {args.root}")

    LIB = Library(args.root)
    LIB.start()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print(f"[sonviaf] http://{args.host}:{args.port}/  root={args.root}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
