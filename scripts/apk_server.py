#!/usr/bin/env python3
    from http.server import HTTPServer, BaseHTTPRequestHandler
    from pathlib import Path
    import threading

    APK_PATH = Path('app/build/outputs/apk/debug/app-debug.apk')
    HOST = '0.0.0.0'
    PORT = 8080

    HTML = """<!doctype html>
    <html lang=de><head>
    <meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
    <title>Weather-LWP APK</title>
    <style>
    body{font-family:system-ui,Segoe UI,Roboto,Arial;margin:40px;max-width:820px}
    a,button{font-size:16px;padding:10px 14px}
    .card{border:1px solid #ddd;border-radius:12px;padding:18px}
    small{color:#666}
    </style></head>
    <body>
    <h1>Weather-LWP APK</h1>
    <div class=card>
      <p><a href=/download>Download APK</a></p>
      <form method=post action=/stop><button type=submit>Stop server</button></form>
      <p><small>Serving: {apk}</small></p>
    </div>
    </body></html>"""

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == '/' or self.path.startswith('/?'):
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.end_headers()
                self.wfile.write(HTML.format(apk=str(APK_PATH)).encode('utf-8'))
                return

            if self.path.startswith('/download'):
                if not APK_PATH.exists():
                    self.send_response(404)
                    self.end_headers()
                    self.wfile.write(f'APK not found: {APK_PATH}'.encode('utf-8'))
                    return
                data = APK_PATH.read_bytes()
                self.send_response(200)
                self.send_header('Content-Type', 'application/vnd.android.package-archive')
                self.send_header('Content-Disposition', f'attachment; filename="{APK_PATH.name}"')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return

            self.send_response(404)
            self.end_headers()

        def do_POST(self):
            if self.path == '/stop':
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain; charset=utf-8')
                self.end_headers()
                self.wfile.write(b'Stopping...
')
                threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            self.send_response(404)
            self.end_headers()

    def main():
        if not APK_PATH.exists():
            raise SystemExit(f'APK not found: {APK_PATH}
Run: ./gradlew assembleDebug')
        httpd = HTTPServer((HOST, PORT), Handler)
        print(f'Serving {APK_PATH} on http://localhost:{PORT} (Stop via button or Ctrl+C)')
        httpd.serve_forever()

    if __name__ == '__main__':
        main()
