#!/usr/bin/env python3
"""breaker/env/flap_proxy.py — Zero-dependency TCP fault-injecting proxy (P27).

Simulates network flapping, mid-stream connection resets (RST/EOF after N bytes),
and slow headers/drip delivery during research uploads (`BRK-015`, `BRK-023`).
Refuses to forward to any upstream host outside `127.0.0.1` or `localhost`.
"""

from __future__ import annotations

import socket
import threading
import time
from dataclasses import dataclass, field


@dataclass
class FlapPolicy:
    """Controls fault injection per proxied TCP connection."""

    drop_every_n: int = 0  # If > 0, reset every Nth connection after `cut_after_bytes`
    cut_after_bytes: int = 512  # Bytes transferred before dropping a flagged connection
    reset_after_bytes: int | None = None  # Optional alias for cut_after_bytes
    chunk_delay_sec: float = 0.0  # Artificial latency per chunk
    accepted_connections: int = field(default=0, init=False)
    dropped_connections: int = field(default=0, init=False)
    _lock: threading.Lock = field(default_factory=threading.Lock, init=False, repr=False)

    def __post_init__(self) -> None:
        if self.reset_after_bytes is not None:
            self.cut_after_bytes = self.reset_after_bytes

    def next_connection_should_drop(self) -> bool:
        with self._lock:
            self.accepted_connections += 1
            if self.drop_every_n > 0 and (self.accepted_connections % self.drop_every_n == 0):
                self.dropped_connections += 1
                return True
            return False


class FlapProxy:
    """Localhost-only fault-injecting TCP proxy for breaker upload resiliency tests."""

    def __init__(
        self,
        target_host: str | None = None,
        target_port: int | None = None,
        policy: FlapPolicy | None = None,
        *,
        upstream_host: str | None = None,
        upstream_port: int | None = None,
    ) -> None:
        host = upstream_host if upstream_host is not None else (target_host or "")
        port = upstream_port if upstream_port is not None else (target_port or 0)
        if host not in ("127.0.0.1", "localhost"):
            raise ValueError(
                f"FlapProxy refuses non-breaker-local upstream host '{host}'."
            )
        self.target_host = host
        self.target_port = port
        self.policy = policy or FlapPolicy()
        self._server_sock: socket.socket | None = None
        self._stop_event = threading.Event()
        self._accept_thread: threading.Thread | None = None
        self.listen_port: int = 0

    def start(self) -> int:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", 0))
        sock.listen(64)
        sock.settimeout(0.2)
        self._server_sock = sock
        self.listen_port = sock.getsockname()[1]
        self._stop_event.clear()
        self._accept_thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._accept_thread.start()
        return self.listen_port

    def stop(self) -> None:
        self._stop_event.set()
        if self._server_sock is not None:
            try:
                self._server_sock.close()
            except OSError:
                pass
            self._server_sock = None
        if self._accept_thread is not None:
            self._accept_thread.join(timeout=2.0)
            self._accept_thread = None

    def __enter__(self) -> FlapProxy:
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.stop()

    def _accept_loop(self) -> None:
        assert self._server_sock is not None
        while not self._stop_event.is_set():
            try:
                client_sock, _ = self._server_sock.accept()
            except TimeoutError:
                continue
            except OSError:
                break
            should_drop = self.policy.next_connection_should_drop()
            t = threading.Thread(
                target=self._handle_client,
                args=(client_sock, should_drop),
                daemon=True,
            )
            t.start()

    def _handle_client(self, client_sock: socket.socket, should_drop: bool) -> None:
        upstream_sock: socket.socket | None = None
        try:
            upstream_sock = socket.create_connection((self.target_host, self.target_port), timeout=5.0)
            if should_drop:
                # Forward at most `cut_after_bytes` from client then abruptly close both sockets
                forwarded = 0
                client_sock.settimeout(2.0)
                while forwarded < self.policy.cut_after_bytes:
                    chunk = client_sock.recv(min(256, self.policy.cut_after_bytes - forwarded))
                    if not chunk:
                        break
                    if self.policy.chunk_delay_sec > 0:
                        time.sleep(self.policy.chunk_delay_sec)
                    upstream_sock.sendall(chunk)
                    forwarded += len(chunk)
                return

            # Normal bidirectional relay
            t_up = threading.Thread(
                target=self._pipe,
                args=(client_sock, upstream_sock),
                daemon=True,
            )
            t_down = threading.Thread(
                target=self._pipe,
                args=(upstream_sock, client_sock),
                daemon=True,
            )
            t_up.start()
            t_down.start()
            t_up.join()
            t_down.join()
        except OSError:
            pass
        finally:
            for s in (client_sock, upstream_sock):
                if s is not None:
                    try:
                        s.close()
                    except OSError:
                        pass

    def _pipe(self, src: socket.socket, dst: socket.socket) -> None:
        try:
            while not self._stop_event.is_set():
                data = src.recv(4096)
                if not data:
                    break
                if self.policy.chunk_delay_sec > 0:
                    time.sleep(self.policy.chunk_delay_sec)
                dst.sendall(data)
        except OSError:
            pass
        finally:
            try:
                dst.shutdown(socket.SHUT_WR)
            except OSError:
                pass
