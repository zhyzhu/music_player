"""Tiny static file server for delivering the APK to the phone over WiFi.

Serves the project root so the built APK can be fetched from the handset's
browser. A plain directory listing is avoided on purpose: the stock browser on
Android 2.2 handles it poorly. "/" returns a small page with a direct link.
"""
import os
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

ROOT = r"C:\Users\zhuzh\Documents\music_player"
PORT = 8000

APK_NAME = "MusicPlayer-1.0-android2.2.apk"
APK_PATH = os.path.join(ROOT, APK_NAME)

PAGE = """<!DOCTYPE html>
<html><head><meta http-equiv="Content-Type" content="text/html; charset=utf-8">
<title>MusicPlayer APK</title></head>
<body style="font-family:sans-serif;padding:16px">
<h3>MusicPlayer (Android 2.2)</h3>
<p><a href="/{apk}">&#19979;&#36733; APK&#65288;{size} KB&#65289;</a></p>
<p style="color:#666;font-size:13px">
&#28857;&#20987;&#19979;&#36733;&#21518;&#65292;&#22312;&#36890;&#30693;&#26639;&#25171;&#24320;&#23436;&#25104;&#23433;&#35013;&#12290;
&#22914;&#26524;&#24050;&#35013;&#26087;&#29256;&#65292;&#30452;&#25509;&#35206;&#30422;&#23433;&#35013;&#21363;&#21487;&#65288;&#31614;&#21517;&#30456;&#21516;&#65292;&#25968;&#25454;&#19981;&#20002;&#65289;&#12290;
</p>
</body></html>
"""


class Handler(BaseHTTPRequestHandler):
    server_version = "MusicPlayerShare/1.0"

    def _send(self, code, ctype, body, extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        self._handle()

    def do_HEAD(self):
        self._handle()

    def _handle(self):
        try:
            path = unquote(urlparse(self.path).path)
        except Exception:
            path = "/"

        if path in ("/", "/index.html"):
            size = os.path.getsize(APK_PATH) // 1024 if os.path.exists(APK_PATH) else 0
            self._send(200, "text/html; charset=utf-8",
                       PAGE.format(apk=APK_NAME, size=size))
            return

        rel = path.lstrip("/").replace("/", os.sep)
        full = os.path.abspath(os.path.join(ROOT, rel))

        # Keep the server inside ROOT.
        if not full.startswith(os.path.abspath(ROOT) + os.sep):
            self._send(403, "text/plain; charset=utf-8", "forbidden")
            return
        if not os.path.isfile(full):
            self._send(404, "text/plain; charset=utf-8", "not found")
            return

        with open(full, "rb") as fh:
            data = fh.read()
        ctype = ("application/vnd.android.package-archive"
                 if full.lower().endswith(".apk") else "application/octet-stream")
        self._send(200, ctype, data, {
            "Content-Disposition": 'attachment; filename="%s"' % os.path.basename(full)
        })

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    if not os.path.exists(APK_PATH):
        raise SystemExit("APK not found: " + APK_PATH)
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("serving %s" % ROOT, flush=True)
    print("open on the phone:  http://%s:%d/" % (lan_ip(), PORT), flush=True)
    httpd.serve_forever()
