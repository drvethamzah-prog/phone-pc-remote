"""Read-only RTT probe; sends only ping messages, never input or pairing commands."""
import argparse
import json
import math
import socket
import statistics
import time
from pathlib import Path


def measure(host, port, count, persistent):
    samples = []
    client = reader = None
    request = json.dumps({"type": "ping", "keepAlive": persistent}).encode() + b"\n"
    try:
        for index in range(count + 1):
            start = time.perf_counter()
            if client is None:
                client = socket.create_connection((host, port), timeout=3)
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                reader = client.makefile("rb")
            client.sendall(request)
            response = json.loads(reader.readline())
            elapsed = (time.perf_counter() - start) * 1000
            if response.get("type") != "pong":
                raise RuntimeError("unexpected_response")
            if persistent and not response.get("persistentConnection"):
                raise RuntimeError("server_does_not_support_persistent_connections")
            if index:
                samples.append(elapsed)
            if not persistent:
                reader.close()
                client.close()
                client = reader = None
    finally:
        if reader is not None:
            reader.close()
        if client is not None:
            client.close()
    return {
        "samples": count, "mean_ms": round(statistics.mean(samples), 3),
        "median_ms": round(statistics.median(samples), 3),
        "p95_ms": round(sorted(samples)[math.ceil(count * .95) - 1], 3),
        "max_ms": round(max(samples), 3),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--samples", type=int, default=100)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not 1 <= args.samples <= 1000:
        parser.error("samples must be between 1 and 1000")
    result = {"host": args.host, "port": args.port, "scope": "request/response RTT from this computer"}
    for persistent in (False, True):
        result["persistent" if persistent else "new_connection"] = measure(args.host, args.port, args.samples, persistent)
    report = json.dumps(result, indent=2)
    print(report)
    if args.output:
        args.output.write_text(report + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
