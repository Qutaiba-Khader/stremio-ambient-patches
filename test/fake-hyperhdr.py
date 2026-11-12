#!/usr/bin/env python3
"""A stand-in for HyperHDR's flatbuffer server, for testing the patch: decodes every message the app sends and keeps
what it saw in a JSON file (one line per event, the last image in full).

  test/fake-hyperhdr.py [--port 19410] [--out /tmp/fake-hyperhdr.jsonl]   (newest image: /tmp/fake-hyperhdr-last.json)

Each image event: width, height, duration, the border colours (top, right, bottom, left as #rrggbb), messages per second.
The decoder follows the flatbuffer rules on its own (no library), checking alignment and bounds as HyperHDR's verifier
would, and reports anything it cannot read as an "error" event.
"""
import argparse, json, os, socket, struct, threading, time

IDLE = 5  # HyperHDR's flatbuffer server drops a client after 5 s without messages (its "timeout" setting)
LAST = "/tmp/fake-hyperhdr-last.json"  # the newest image, rewritten on every image
COMMANDS = {1: "color", 2: "image", 3: "clear", 4: "register"}


class Bad(Exception):
    pass


class Msg:
    def __init__(self, b):
        self.b = b

    def u32(self, at):
        if at < 0 or at + 4 > len(self.b):
            raise Bad(f"read outside the message at {at}")
        return struct.unpack_from("<I", self.b, at)[0]

    def i32(self, at):
        return struct.unpack_from("<i", self.b, at)[0]

    def u16(self, at):
        return struct.unpack_from("<H", self.b, at)[0]

    def table(self, t):
        if t % 4:
            raise Bad(f"table at {t} not 4-aligned")
        vt = t - self.i32(t)
        if vt < 0 or vt % 2 or vt + 4 > len(self.b):
            raise Bad(f"vtable of {t} at {vt} outside or unaligned")
        return t

    def field(self, t, slot):
        vt = t - self.i32(t)
        vsize = self.u16(vt)
        if 4 + 2 * slot >= vsize:
            return None
        o = self.u16(vt + 4 + 2 * slot)
        return None if o == 0 else t + o

    def int_(self, t, slot, default):
        f = self.field(t, slot)
        return default if f is None else self.i32(f)

    def byte(self, t, slot):
        f = self.field(t, slot)
        return 0 if f is None else self.b[f]

    def ref(self, t, slot):
        f = self.field(t, slot)
        if f is None:
            raise Bad(f"required field {slot} of table {t} missing")
        return f + self.u32(f)

    def vector(self, at):
        if at % 4:
            raise Bad(f"vector at {at} not 4-aligned")
        n = self.u32(at)
        if at + 4 + n > len(self.b):
            raise Bad("vector outside the message")
        return self.b[at + 4:at + 4 + n]


def decode(b):
    m = Msg(b)
    root = m.table(m.u32(0))
    kind = COMMANDS.get(m.byte(root, 0), f"unknown {m.byte(root, 0)}")
    cmd = m.table(m.ref(root, 1))
    if kind == "register":
        origin = m.vector(m.ref(cmd, 0)).decode()
        return {"event": "register", "origin": origin, "priority": m.int_(cmd, 1, 0)}
    if kind == "clear":
        return {"event": "clear", "priority": m.int_(cmd, 0, 0)}
    if kind == "image":
        if m.byte(cmd, 0) != 1:
            raise Bad("image is not a RawImage")
        raw = m.table(m.ref(cmd, 1))
        w, h = m.int_(raw, 1, -1), m.int_(raw, 2, -1)
        data = m.vector(m.ref(raw, 0))
        if len(data) != w * h * 3:
            raise Bad(f"{len(data)} bytes for {w}x{h}")
        px = lambda x, y: "#%02x%02x%02x" % tuple(data[3 * (y * w + x):3 * (y * w + x) + 3])
        return {"event": "image", "w": w, "h": h, "duration": m.int_(cmd, 2, -1),
                "top": [px(x, 0) for x in range(w)], "bottom": [px(x, h - 1) for x in range(w)],
                "left": [px(0, y) for y in range(1, h - 1)], "right": [px(w - 1, y) for y in range(1, h - 1)],
                "inside": px(w // 2, h // 2)}
    return {"event": kind}


def serve(conn, addr, out, lock):
    n, t0 = 0, time.time()
    last = None
    conn.settimeout(IDLE)
    with conn:
        buf = b""
        while True:
            try:
                chunk = conn.recv(65536)
            except socket.timeout:
                break  # like HyperHDR: a client silent for IDLE seconds is dropped
            if not chunk:
                break
            buf += chunk
            while len(buf) >= 4:
                size = struct.unpack(">I", buf[:4])[0]
                if len(buf) < 4 + size:
                    break
                body, buf = buf[4:4 + size], buf[4 + size:]
                try:
                    ev = decode(body)
                except Exception as e:
                    ev = {"event": "error", "error": str(e), "size": size}
                ev["t"] = round(time.time(), 3)
                ev["from"] = addr[0]
                if ev["event"] == "image":
                    n += 1
                    ev["rate"] = round(n / max(0.001, time.time() - t0), 1)
                    last = ev
                    with open(LAST + ".tmp", "w") as f:
                        json.dump(ev, f)
                    os.replace(LAST + ".tmp", LAST)  # whole files only: a reader never sees half a write
                    if n % 20 != 1:
                        continue  # one image line in 20, plus the last one when the connection ends
                with lock:
                    out.write(json.dumps(ev) + "\n")
                    out.flush()
    with lock:
        if last:
            last = dict(last, event="last-image")
            out.write(json.dumps(last) + "\n")
        out.write(json.dumps({"event": "closed", "t": round(time.time(), 3), "images": n}) + "\n")
        out.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=19410)
    ap.add_argument("--out", default="/tmp/fake-hyperhdr.jsonl")
    a = ap.parse_args()
    lock = threading.Lock()
    out = open(a.out, "a")
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", a.port))
    s.listen(4)
    while True:
        c, addr = s.accept()
        threading.Thread(target=serve, args=(c, addr, out, lock), daemon=True).start()


if __name__ == "__main__":
    main()
