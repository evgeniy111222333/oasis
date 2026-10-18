"""Read-only Minecraft status handshake; never logs a player into the server."""
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import socket
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def varint(value):
    value &= 0xffffffff
    data = bytearray()
    while value > 127:
        data.append((value & 127) | 128)
        value >>= 7
    data.append(value)
    return bytes(data)


def read_exact(connection, size):
    data = bytearray()
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise EOFError("Server closed the status connection")
        data.extend(chunk)
    return bytes(data)


def read_varint(connection):
    value = 0
    for shift in range(0, 35, 7):
        byte = read_exact(connection, 1)[0]
        value |= (byte & 127) << shift
        if byte < 128:
            return value
    raise ValueError("Invalid status VarInt")


def main():
    with socket.create_connection(("127.0.0.1", 25565), timeout=5) as connection:
        connection.settimeout(10)
        host = b"localhost"
        handshake = b"\x00" + varint(-1) + varint(len(host)) + host + struct.pack(">H", 25565) + b"\x01"
        connection.sendall(varint(len(handshake)) + handshake + b"\x01\x00")
        packet_length = read_varint(connection)
        assert 0 < packet_length <= 1_048_576
        assert read_varint(connection) == 0
        size = read_varint(connection)
        assert 0 < size <= packet_length
        status = json.loads(read_exact(connection, size).decode("utf-8"))
    assert status["version"]["name"] == "26.1.2", status["version"]
    for port in [25580]:
        with socket.create_connection(("127.0.0.1", port), timeout=5):
            pass
    stdout = (HERE / "server-start.stdout.log").read_text(encoding="utf-8-sig", errors="replace")
    stderr = (HERE / "server-start.stderr.log").read_text(encoding="utf-8-sig", errors="replace")
    assert "Done (" in stdout and "eclipseserver 1.5.34" in stdout
    assert not any(message in stdout + stderr for message in [
        "Physical carry disabled:", "Critical injection failure", "Mixin apply failed", "Exception starting the server",
    ])
    ledger = ROOT / "fabric-server/world/rpchat-carry.bin"
    profiles = ROOT / "fabric-server/config/RPChat/carry-profiles.properties"
    assert ledger.is_file() and profiles.is_file()
    assert ledger.read_bytes()[:4] == b"CAR1"
    runtime = ROOT / "fabric-server/mods/eclipseserver-1.5.34.jar"
    built = ROOT / "fabric-server/server/build/libs/server-1.5.34.jar"
    checksum = sha256(runtime.read_bytes()).hexdigest()
    assert checksum == sha256(built.read_bytes()).hexdigest()
    with zipfile.ZipFile(runtime) as jar:
        assert json.loads(jar.read("fabric.mod.json"))["version"] == "1.5.34"
    report = {
        "verified_utc": datetime.now(timezone.utc).isoformat(), "address": "localhost:25565",
        "status_handshake": "ok", "minecraft": status["version"],
        "players_online": status["players"]["online"], "auth_port": 25580,
        "server_mod_version": "1.5.34", "server_mod_sha256": checksum,
        "carry_ledger_initialized": True, "carry_profiles": str(profiles),
        "startup_errors_detected": False, "game_client_launched": False,
    }
    (HERE / "local-server-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
