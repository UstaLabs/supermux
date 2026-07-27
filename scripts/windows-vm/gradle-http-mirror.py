#!/usr/bin/env python3
"""Restricted HTTP bridge for Windows VMs whose Java TLS stack cannot reach Maven."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import shutil
import urllib.error
import urllib.request

UPSTREAMS = {
    "/plugin/": "https://plugins.gradle.org/m2/",
    "/central/": "https://repo.maven.apache.org/maven2/",
    "/google/": "https://dl.google.com/dl/android/maven2/",
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_HEAD(self) -> None:
        self._proxy(False)

    def do_GET(self) -> None:
        self._proxy(True)

    def _proxy(self, include_body: bool) -> None:
        match = next(((prefix, upstream) for prefix, upstream in UPSTREAMS.items()
                      if self.path.startswith(prefix)), None)
        if match is None or ".." in self.path:
            self.send_error(404)
            return
        prefix, upstream = match
        url = upstream + self.path[len(prefix):]
        try:
            request = urllib.request.Request(url, method=self.command)
            with urllib.request.urlopen(request, timeout=60) as response:
                self.send_response(response.status)
                for name in ("Content-Type", "Content-Length", "Last-Modified", "ETag"):
                    value = response.headers.get(name)
                    if value:
                        self.send_header(name, value)
                self.end_headers()
                if include_body:
                    shutil.copyfileobj(response, self.wfile)
        except urllib.error.HTTPError as error:
            self.send_error(error.code, error.reason)
        except Exception as error:
            self.send_error(502, str(error))

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.address_string()} {fmt % args}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8766)
    args = parser.parse_args()
    ThreadingHTTPServer((args.bind, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()

