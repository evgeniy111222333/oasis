"""Local server save/stop probe. Credentials stay in server.properties and are never printed."""
import argparse
import socket
import struct
from pathlib import Path

ROOT = Path('D:/23/fabric-server')

def receive(connection):
    def exact(count):
        output = b''
        while len(output) < count:
            chunk = connection.recv(count - len(output))
            if not chunk:
                raise EOFError('Server closed the RCON connection')
            output += chunk
        return output
    length = struct.unpack('<i', exact(4))[0]
    if not 10 <= length <= 4_194_304:
        raise ValueError('Invalid RCON response length')
    packet = exact(length)
    request, kind = struct.unpack('<ii', packet[:8])
    return request, kind, packet[8:-2].decode('utf-8', errors='replace')

def send(connection, request, kind, body):
    payload = struct.pack('<ii', request, kind) + body.encode('utf-8') + b'\0\0'
    connection.sendall(struct.pack('<i', len(payload)) + payload)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['probe', 'save-stop', 'attributes'])
    args = parser.parse_args()
    properties = dict(line.split('=', 1) for line in (ROOT/'server.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    with socket.create_connection(('127.0.0.1', int(properties.get('rcon.port', 25575))), timeout=20) as connection:
        send(connection, 73, 3, properties['rcon.password'])
        while True:
            request, kind, _ = receive(connection)
            if request == -1:
                raise RuntimeError('RCON authentication failed')
            if kind == 2:
                break
        commands = ['list'] if args.action == 'probe' else ['save-all flush', 'stop'] if args.action == 'save-stop' \
                else ['execute as @a run attribute @s minecraft:movement_speed get']
        for index, command in enumerate(commands, start=74):
            send(connection, index, 2, command)
            try:
                request, _, response = receive(connection)
                if request != index:
                    raise RuntimeError('Unexpected RCON response ID')
                print(command + ': ' + response)
            except EOFError:
                if command != 'stop':
                    raise
                print('stop: server closed the connection')

if __name__ == '__main__':
    main()
