"""Local, isolated HTTP benchmark. Starts/stops only its own Java process."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import ctypes
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import platform
import socket
import statistics
import subprocess
import threading
import time
from urllib.parse import urlencode


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)]


def rss_bytes(pid):
    if os.name == "nt":
        from ctypes import wintypes
        class Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [(name, ctypes.c_size_t) for name in
                ["PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage"]]
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        handle = kernel.OpenProcess(0x0400 | 0x0010, False, pid)
        if not handle: return None
        try:
            result = Counters(); result.cb = ctypes.sizeof(result)
            return result.WorkingSetSize if psapi.GetProcessMemoryInfo(handle, ctypes.byref(result), result.cb) else None
        finally: kernel.CloseHandle(handle)
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"): return int(line.split()[1]) * 1024
    except OSError: pass
    return None


def request(connection, path, method="GET"):
    start = time.perf_counter()
    connection.request(method, path, headers={"Accept": "application/json"})
    response = connection.getresponse(); body = response.read()
    elapsed = (time.perf_counter() - start) * 1000
    if response.status != 200: raise RuntimeError(f"HTTP {response.status} en {path}: {body[:200]!r}")
    return elapsed, body


def measure(port, path, count, concurrency, pid, cold=False):
    samples, memory = [], []
    stop = threading.Event()
    def sample_memory():
        while not stop.is_set():
            value = rss_bytes(pid)
            if value is not None: memory.append(value)
            stop.wait(0.1)
    sampler = threading.Thread(target=sample_memory, daemon=True); sampler.start()
    def worker(n):
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
        result = []
        try:
            for _ in range(n):
                if cold: request(connection, "/internal/benchmark/reset", "POST")
                result.append(request(connection, path)[0])
            return result
        finally: connection.close()
    start = time.perf_counter()
    try:
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            for values in pool.map(worker, [count // concurrency + (i < count % concurrency) for i in range(concurrency)]):
                samples.extend(values)
    finally:
        elapsed = time.perf_counter() - start; stop.set(); sampler.join()
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
    try: heap = json.loads(request(connection, "/internal/benchmark/memory")[1])
    finally: connection.close()
    return {"requests": len(samples), "concurrency": concurrency, "elapsed_seconds": elapsed,
            "requests_per_second": len(samples) / elapsed, "p50_ms": percentile(samples, .50),
            "p95_ms": percentile(samples, .95), "p99_ms": percentile(samples, .99),
            "mean_ms": statistics.mean(samples), "rss_peak_bytes": max(memory) if memory else None,
            **heap, "cache_reset_control_in_throughput": cold}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--requests", type=int, default=1000)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--warmup", type=int, default=300)
    parser.add_argument("--cold-requests", type=int, default=50)
    parser.add_argument("--output", type=Path, default=Path("target/benchmark.json"))
    args = parser.parse_args()
    if min(args.requests, args.concurrency, args.warmup, args.cold_requests) < 1:
        parser.error("Todos los contadores deben ser positivos")
    if not args.jar.is_file(): parser.error("El JAR no existe; ejecuta mvn package")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0)); port = listener.getsockname()[1]
    command = [args.java, "-Xms128m", "-Xmx384m", "-XX:+UseZGC", "-XX:+UseCompactObjectHeaders", "-jar", str(args.jar.resolve()),
               "--spring.profiles.active=benchmark", f"--server.port={port}", f"--management.server.port={port}",
               "--server.address=127.0.0.1", "--server.forward-headers-strategy=none", "--spring.main.banner-mode=off",
               "--zipcode.catalog.directory=", "--zipcode.file.path=classpath:CPdescarga.txt", "--zipcode.file.encoding=auto",
               "--ratelimit.enabled=false", "--cache.warmup.enabled=false"]
    with args.output.with_suffix(".server.log").open("w", encoding="utf-8") as log:
        started = time.perf_counter()
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            for _ in range(600):
                if process.poll() is not None: raise RuntimeError("El servidor terminó; revisa el log")
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
                try:
                    request(connection, "/actuator/health/readiness"); break
                except (OSError, RuntimeError, http.client.HTTPException): time.sleep(.1)
                finally: connection.close()
            else: raise RuntimeError("Timeout esperando readiness")
            startup = time.perf_counter() - started
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            try: catalog = json.loads(request(connection, "/zip-codes/stats")[1])
            finally: connection.close()
            paths = {"direct": "/zip-codes/01000", "municipality": "/zip-codes/by-municipality?municipality=Guadalajara&size=20",
                     "advanced": "/zip-codes/advanced?" + urlencode({"settlement": "san", "zone_type": "Urbano", "size": 20})}
            report = {"platform": platform.platform(), "cpu_count": os.cpu_count(), "command": command,
                "java": subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True).stderr.strip(),
                "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(), "catalog": catalog,
                "startup_seconds": startup, "warmup_requests_per_scenario": args.warmup, "scenarios": {}}
            for name, path in paths.items():
                measure(port, path, args.warmup, 1, process.pid)
                if name != "direct":
                    report["scenarios"][name + "_cold_cache"] = measure(port, path, args.cold_requests, 1, process.pid, cold=True)
                report["scenarios"][name + "_warm"] = measure(port, path, args.requests, args.concurrency, process.pid)
            args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            print(json.dumps(report, indent=2, ensure_ascii=False))
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)


if __name__ == "__main__": main()
