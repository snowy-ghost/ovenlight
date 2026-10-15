"""A test app for e2e.sh: serves its folder on $PORT, and echoes WebSocket messages at /ws.

The standard library has no WebSocket server, so this is the least of one: the handshake,
then text frames echoed back until the client closes.
"""

import base64
import hashlib
import http.server
import os
import struct
import sys

GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
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

    def log_message(self, format, *args):
        sys.stderr.write("%s %s\n" % (self.path, format % args))


http.server.ThreadingHTTPServer(("127.0.0.1", int(os.environ["PORT"])), Handler).serve_forever()
