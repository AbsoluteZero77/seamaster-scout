# Desktop launcher: python server.py [port]  ->  opens http://localhost:8000
import http.server, urllib.request, urllib.parse, os, sys, time, ipaddress, socket, threading, webbrowser
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), "www"))
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"
CACHE, TTL = {}, 300  # 5 minute cache keeps requests polite and updates fast

def safe(url):
    u = urllib.parse.urlparse(url)
    if u.scheme != "https" or not u.hostname: return False
    try:
        ip = ipaddress.ip_address(socket.gethostbyname(u.hostname))
        return not (ip.is_private or ip.is_loopback or ip.is_link_local)
    except Exception: return False

class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if not self.path.startswith("/proxy?url="): return super().do_GET()
        url = urllib.parse.unquote(self.path[11:])
        if not safe(url): return self.send_error(403, "Blocked URL")
        hit = CACHE.get(url)
        if hit and time.time() - hit[0] < TTL: body = hit[1]
        else:
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ja,en;q=0.8"})
                r = urllib.request.urlopen(req, timeout=20); raw = r.read()
                body = raw.decode(r.headers.get_content_charset() or "utf-8", "replace").encode("utf-8")  # e.g. Shift_JIS shop pages
                CACHE[url] = (time.time(), body)
            except Exception as e: return self.send_error(502, str(e)[:80])
        self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

srv = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), H)
print(f"Running at http://localhost:{PORT}  (Ctrl+C to stop)")
threading.Timer(0.8, lambda: webbrowser.open(f"http://localhost:{PORT}")).start()
srv.serve_forever()
