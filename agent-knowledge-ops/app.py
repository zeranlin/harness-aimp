from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8004

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")
            return
        self.send_response(404)
        self.end_headers()

if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"agent-knowledge-ops listening on {PORT}")
    server.serve_forever()
