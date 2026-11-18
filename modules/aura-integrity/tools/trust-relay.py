#!/usr/bin/env python3
"""CI-only CONNECT relay: preserves end-to-end Google TLS, sends no credentials."""
import select,socket,socketserver
class Relay(socketserver.StreamRequestHandler):
    def handle(self):
        line=self.rfile.readline(4096).decode('ascii','replace').strip()
        if line!='CONNECT android.googleapis.com:443 HTTP/1.1' and line!='CONNECT android.googleapis.com:443 HTTP/1.0':
            self.wfile.write(b'HTTP/1.1 403 Forbidden\r\n\r\n');return
        for _ in range(64):
            if self.rfile.readline(4096) in (b'\r\n',b'\n',b''):break
        with socket.create_connection(('android.googleapis.com',443),timeout=20) as upstream:
            self.wfile.write(b'HTTP/1.1 200 Connection Established\r\n\r\n');self.wfile.flush()
            for source in (self.connection,upstream):source.settimeout(30)
            while True:
                ready,_,_=select.select([self.connection,upstream],[],[],30)
                if not ready:return
                for source in ready:
                    block=source.recv(65536)
                    if not block:return
                    (upstream if source is self.connection else self.connection).sendall(block)
class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True
with Server(('127.0.0.1',18743),Relay) as server:server.serve_forever()
