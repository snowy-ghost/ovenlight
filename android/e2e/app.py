"""A test app for e2e.sh: serves its folder on $PORT, and echoes WebSocket messages at /ws.

For the downloads scenario, /session sets an HttpOnly cookie, and /private.csv is an
attachment only with that cookie (403 without it). For the upload scenario, a POST to /upload
keeps its body as uploaded/<X-Name> in the folder.

The standard library has no WebSocket server, so this is the least of one: the handshake,
then text frames echoed back until the client closes.
"""

import base64
import hashlib
import http.server
import os
import struct
import sys
import urllib.parse

GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class Handler(http.server.SimpleHTTPRequestHandler):
    def reply(self, code, body, headers=()):
        self.send_response(code)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/session":
            return self.reply(200, b"ok", [("Set-Cookie", "e2e=1; Secure; HttpOnly; Path=/; SameSite=Lax")])
        if self.path == "/private.csv":
            if "e2e=1" not in self.headers.get("Cookie", ""):
                return self.reply(403, b"no session")
            return self.reply(200, b"name,amount\nflour,500\n", [("Content-Type", "text/csv"), ("Content-Disposition", 'attachment; filename="server.csv"')])
        if self.path != "/ws" or self.headers.get("Upgrade", "").lower() != "websocket":
            return super().do_GET()
        accept = base64.b64encode(hashlib.sha1(self.headers["Sec-WebSocket-Key"].encode() + GUID).digest())
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept.decode())
        self.end_headers()
        self.wfile.flush()
        while True:
            head = self.rfile.read(2)
            if len(head) < 2:
                return
            opcode, length = head[0] & 0x0F, head[1] & 0x7F
            if length == 126:
                length = struct.unpack(">H", self.rfile.read(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self.rfile.read(8))[0]
            mask = self.rfile.read(4) if head[1] & 0x80 else b"\0\0\0\0"
            data = bytes(b ^ mask[i % 4] for i, b in enumerate(self.rfile.read(length)))
            if opcode == 8:
                return
            if opcode == 1:
                frame = bytes([0x81]) + (bytes([len(data)]) if len(data) < 126 else bytes([126]) + struct.pack(">H", len(data)))
                self.wfile.write(frame + data)
                self.wfile.flush()

    def do_POST(self):
        if self.path != "/upload":
            return self.reply(404, b"no such endpoint")
        name = os.path.basename(urllib.parse.unquote(self.headers.get("X-Name", ""))) or "upload"
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        os.makedirs("uploaded", exist_ok=True)
        with open(os.path.join("uploaded", name), "wb") as f:
            f.write(body)
        return self.reply(200, str(len(body)).encode())

    def log_message(self, format, *args):
        sys.stderr.write("%s %s\n" % (self.path, format % args))


http.server.ThreadingHTTPServer(("127.0.0.1", int(os.environ["PORT"])), Handler).serve_forever()
