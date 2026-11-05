import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from yt_dlp import YoutubeDL

PORT = int(os.environ.get("PORT", "8000"))
URL_TTL = 12 * 60
CACHE = {}
CACHE_LOCK = threading.Lock()
VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")


def search(query, limit=15):
    options = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "socket_timeout": 12,
    }
    with YoutubeDL(options) as ydl:
        data = ydl.extract_info("ytsearch%d:%s" % (limit, query), download=False)
    results = []
    for entry in data.get("entries") or []:
        if not entry or not entry.get("id"):
            continue
        video_id = entry["id"]
        thumbnails = entry.get("thumbnails") or []
        results.append({
            "id": video_id,
            "title": entry.get("title") or "",
            "artist": entry.get("uploader") or entry.get("channel") or "YouTube",
            "thumbnail": (thumbnails[-1].get("url") if thumbnails else None)
                         or "https://i.ytimg.com/vi/%s/mqdefault.jpg" % video_id,
            "duration": int(entry.get("duration") or 0),
        })
    return results


def audio_url(video_id):
    now = time.time()
    with CACHE_LOCK:
        cached = CACHE.get(video_id)
        if cached and cached[0] > now:
            return cached[1]
    options = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "format": "bestaudio[ext=m4a]/bestaudio/best",
        "socket_timeout": 15,
    }
    with YoutubeDL(options) as ydl:
        info = ydl.extract_info("https://www.youtube.com/watch?v=" + video_id, download=False)
    url = info.get("url")
    if not url:
        raise RuntimeError("yt-dlp did not return an audio URL")
    with CACHE_LOCK:
        CACHE[video_id] = (time.time() + URL_TTL, url)
    return url


class Handler(BaseHTTPRequestHandler):
    server_version = "StrawhatMusic/1.0"

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args))

    def json_response(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)
        try:
            if parsed.path == "/health":
                return self.json_response(200, {"ok": True, "service": "strawhat-yt-dlp"})
            if parsed.path == "/api/search":
                query = (params.get("q") or [""])[0].strip()
                if not query:
                    return self.json_response(400, {"error": "Missing search query"})
                if len(query) > 180:
                    return self.json_response(400, {"error": "Search query is too long"})
                return self.json_response(200, search(query))
            if parsed.path == "/api/stream":
                video_id = (params.get("id") or [""])[0].strip()
                if not VIDEO_ID.fullmatch(video_id):
                    return self.json_response(400, {"error": "Invalid YouTube video ID"})
                self.send_response(302)
                self.send_header("Location", audio_url(video_id))
                self.send_header("Cache-Control", "no-store")
                self.send_header("Referrer-Policy", "no-referrer")
                self.end_headers()
                return
            return self.json_response(404, {"error": "Not found"})
        except Exception as exc:
            return self.json_response(502, {"error": str(exc)[:300]})


if __name__ == "__main__":
    print("Strawhat yt-dlp backend listening on port %s" % PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
