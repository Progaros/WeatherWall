#!/usr/bin/env python3
"""
Simple HTTP server for APK distribution in local network.
Start with: python3 scripts/apk_server.py
Stop via web UI button or Ctrl+C
"""
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import threading
import os
import socket

APK_PATH = Path('app/build/outputs/apk/debug/app-debug.apk')
HOST = '0.0.0.0'
PORT = 8080

HTML = """<!DOCTYPE html>
<html lang="de">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>WeatherWall APK Download</title>
    <style>
        * { box-sizing: border-box; }
        body {
            font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
            margin: 0;
            padding: 40px 20px;
            min-height: 100vh;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: #fff;
        }
        .container {
            max-width: 500px;
            margin: 0 auto;
        }
        h1 {
            font-size: 2rem;
            margin-bottom: 8px;
            text-shadow: 0 2px 4px rgba(0,0,0,0.2);
        }
        .subtitle {
            opacity: 0.9;
            margin-bottom: 30px;
        }
        .card {
            background: rgba(255,255,255,0.95);
            color: #333;
            border-radius: 16px;
            padding: 24px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.2);
        }
        .status {
            display: flex;
            align-items: center;
            gap: 10px;
            margin-bottom: 20px;
            padding: 12px;
            background: #e8f5e9;
            border-radius: 8px;
            color: #2e7d32;
        }
        .status-dot {
            width: 12px;
            height: 12px;
            background: #4caf50;
            border-radius: 50%;
            animation: pulse 2s infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.5; }
        }
        .btn {
            display: inline-block;
            padding: 14px 28px;
            font-size: 16px;
            font-weight: 600;
            text-decoration: none;
            border: none;
            border-radius: 8px;
            cursor: pointer;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        .btn:hover {
            transform: translateY(-2px);
            box-shadow: 0 4px 12px rgba(0,0,0,0.15);
        }
        .btn-primary {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
        }
        .btn-danger {
            background: #f44336;
            color: white;
        }
        .actions {
            display: flex;
            gap: 12px;
            flex-wrap: wrap;
            margin-bottom: 20px;
        }
        .info {
            font-size: 14px;
            color: #666;
            margin-top: 16px;
            padding-top: 16px;
            border-top: 1px solid #eee;
        }
        .info-row {
            display: flex;
            justify-content: space-between;
            margin: 4px 0;
        }
        .info-label {
            color: #999;
        }
        .qr-hint {
            margin-top: 20px;
            padding: 12px;
            background: #fff3e0;
            border-radius: 8px;
            font-size: 14px;
            color: #e65100;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🌦️ WeatherWall</h1>
        <p class="subtitle">APK Download Server</p>
        
        <div class="card">
            <div class="status">
                <div class="status-dot"></div>
                <span>Server aktiv</span>
            </div>
            
            <div class="actions">
                <a href="/download" class="btn btn-primary">📥 APK herunterladen</a>
                <form method="post" action="/stop" style="margin:0">
                    <button type="submit" class="btn btn-danger">⏹ Server stoppen</button>
                </form>
            </div>
            
            <div class="info">
                <div class="info-row">
                    <span class="info-label">Datei:</span>
                    <span>{apk_name}</span>
                </div>
                <div class="info-row">
                    <span class="info-label">Größe:</span>
                    <span>{apk_size}</span>
                </div>
                <div class="info-row">
                    <span class="info-label">Server:</span>
                    <span>http://{local_ip}:{port}</span>
                </div>
            </div>
            
            <div class="qr-hint">
                💡 <strong>Tipp:</strong> Öffne diese URL auf deinem Android-Gerät im selben Netzwerk.
            </div>
        </div>
    </div>
</body>
</html>"""

STOPPED_HTML = """<!DOCTYPE html>
<html lang="de">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Server gestoppt</title>
    <style>
        body {
            font-family: system-ui, sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            margin: 0;
            background: #f5f5f5;
            color: #333;
        }
        .card {
            text-align: center;
            padding: 40px;
            background: white;
            border-radius: 16px;
            box-shadow: 0 4px 20px rgba(0,0,0,0.1);
        }
        h1 { color: #4caf50; }
    </style>
</head>
<body>
    <div class="card">
        <h1>✅ Server gestoppt</h1>
        <p>Du kannst dieses Fenster schließen.</p>
    </div>
</body>
</html>"""


def get_local_ip():
    """Get local IP address for display."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "localhost"


def format_size(size_bytes):
    """Format file size in human readable format."""
    for unit in ['B', 'KB', 'MB', 'GB']:
        if size_bytes < 1024:
            return f"{size_bytes:.1f} {unit}"
        size_bytes /= 1024
    return f"{size_bytes:.1f} GB"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        """Custom logging."""
        print(f"[{self.log_date_time_string()}] {args[0]}")

    def do_GET(self):
        if self.path == '/' or self.path.startswith('/?'):
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            
            apk_size = format_size(APK_PATH.stat().st_size) if APK_PATH.exists() else "N/A"
            local_ip = get_local_ip()
            
            html = HTML.format(
                apk_name=APK_PATH.name,
                apk_size=apk_size,
                local_ip=local_ip,
                port=PORT
            )
            self.wfile.write(html.encode('utf-8'))
            return

        if self.path.startswith('/download'):
            if not APK_PATH.exists():
                self.send_response(404)
                self.send_header('Content-Type', 'text/plain; charset=utf-8')
                self.end_headers()
                self.wfile.write(f'APK nicht gefunden: {APK_PATH}'.encode('utf-8'))
                return
            
            data = APK_PATH.read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'application/vnd.android.package-archive')
            self.send_header('Content-Disposition', f'attachment; filename="WeatherWall-debug.apk"')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            print(f"✅ APK heruntergeladen von {self.client_address[0]}")
            return

        self.send_response(404)
        self.send_header('Content-Type', 'text/plain')
        self.end_headers()
        self.wfile.write(b'Not found')

    def do_POST(self):
        if self.path == '/stop':
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(STOPPED_HTML.encode('utf-8'))
            print("\n🛑 Server wird gestoppt...")
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        
        self.send_response(404)
        self.end_headers()


def main():
    if not APK_PATH.exists():
        print(f"❌ APK nicht gefunden: {APK_PATH}")
        print("   Bitte zuerst bauen:")
        print("   ./gradlew assembleDebug")
        raise SystemExit(1)

    local_ip = get_local_ip()
    apk_size = format_size(APK_PATH.stat().st_size)

    print("=" * 50)
    print("🌦️  WeatherWall APK Server")
    print("=" * 50)
    print(f"📦 APK:    {APK_PATH.name} ({apk_size})")
    print(f"🌐 Lokal:  http://localhost:{PORT}")
    print(f"📱 LAN:    http://{local_ip}:{PORT}")
    print("=" * 50)
    print("Stoppen: Button im Browser oder Ctrl+C")
    print()

    httpd = HTTPServer((HOST, PORT), Handler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n🛑 Server gestoppt (Ctrl+C)")
        httpd.shutdown()


if __name__ == '__main__':
    main()
