"""Small local MCP client for importing and verifying this animation artifact."""
from pathlib import Path
import argparse
import base64
import json
import urllib.request

OUT = Path(__file__).resolve().parent
URL = 'http://[::1]:3000/bb-mcp'


class Client:
    def __init__(self):
        self.session = None
        self.counter = 0
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.rpc('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {},
                               'clientInfo': {'name': 'codex-carver-animation', 'version': '1.0'}})
        self.rpc('notifications/initialized', notification=True)

    def rpc(self, method, params=None, notification=False):
        self.counter += 1
        payload = {'jsonrpc': '2.0', 'method': method}
        if not notification:
            payload['id'] = self.counter
        if params is not None:
            payload['params'] = params
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
        if self.session:
            headers.update({'Mcp-Session-Id': self.session, 'MCP-Protocol-Version': '2024-11-05'})
        req = urllib.request.Request(URL, data=json.dumps(payload).encode(), headers=headers)
        with self.opener.open(req, timeout=45) as response:
            self.session = response.headers.get('mcp-session-id') or self.session
            if response.status == 202:
                return None
            if 'event-stream' in response.headers.get('Content-Type', ''):
                for line in response:
                    if line.startswith(b'data:'):
                        return json.loads(line[5:])
            else:
                body = response.read()
                return json.loads(body) if body else None

    def tool(self, name, arguments):
        response = self.rpc('tools/call', {'name': name, 'arguments': arguments})
        if 'error' in response:
            raise RuntimeError(response['error'])
        result = response['result']
        if result.get('isError'):
            raise RuntimeError(result.get('content'))
        return result

    def evaluate(self, code):
        result = self.tool('risky_eval', {'code': code})
        content = next(c['text'] for c in result['content'] if c['type'] == 'text')
        value = json.loads(content)
        if isinstance(value, str):
            try:
                return json.loads(value)
            except json.JSONDecodeError:
                pass
        return value

    def close(self):
        if self.session:
            req = urllib.request.Request(URL, method='DELETE', headers={'Mcp-Session-Id': self.session})
            try:
                self.opener.open(req, timeout=5).close()
            except Exception:
                pass


def load(client):
    path = OUT / 'eclipse_carver_first_cut.bbmodel'
    code = """(async () => {
        const previous=Project;
        if (previous && !['Eclipse_Carver_First_Cut','eclipse_carver_first_cut','Eclipse — Carver: First Cut'].includes(previous.name))
            throw new Error('Reload only supports the preview project belonging to this task');
        if(previous) { previous.saved=true; await previous.close(); }
        return await new Promise(resolve => Blockbench.read([PATH],{errorbox:false},files=>{
            Codecs.project.load(JSON.parse(files[0].content),files[0]);
            Project.name = 'Eclipse_Carver_First_Cut';
            Project.saved = true;
            Canvas.updateAll();
            resolve(JSON.stringify({name:Project.name,elements:Cube.all.length,
                clips:Animation.all.map(a=>({name:a.name,length:a.length})),
                textures:Texture.all.length}));
        }));
    })()""".replace('PATH', json.dumps(str(path)))
    print(json.dumps(client.evaluate(code), ensure_ascii=False))
    print(json.dumps(client.tool('set_mode', {'mode_id': 'animate'})['content'], ensure_ascii=False))


def setup(client):
    code = """(() => {
        if (Project.name !== 'Eclipse_Carver_First_Cut') throw new Error('Wrong project');
        if (Timeline.playing) Timeline.pause();
        const a=Animation.all.find(a=>a.name==='00_Showcase'); a.select();
        const p=Preview.all.find(p=>p.id==='main');
        p.setProjectionMode(false);
        p.camera.position.set(50,35,-51);
        p.controls.target.set(0,15,-5);
        p.controls.update();
        Timeline.setTime(1.9); Animator.preview();
        return JSON.stringify({clip:a.name,eulerOrder:Format.euler_order,viewport:p.id});
    })()"""
    print(json.dumps(client.evaluate(code)))


def capture(client, time, path):
    code = """(async () => {
        if (Project.name !== 'Eclipse_Carver_First_Cut') throw new Error('Wrong project');
        if (Timeline.playing) Timeline.pause();
        const a=Animation.all.find(a=>a.name==='00_Showcase');
        if (Animation.selected!==a) a.select();
        Timeline.setTime(TIME); Animator.preview();
        const p=Preview.all.find(p=>p.id==='main');
        p.render();
        return await new Promise(resolve=>p.screenshot({width:960,height:720},resolve));
    })()""".replace('TIME', str(float(time)))
    data = client.evaluate(code)
    if not isinstance(data, str) or not data.startswith('data:image/'):
        raise RuntimeError(f'Unexpected screenshot result: {str(data)[:180]}')
    path.write_bytes(base64.b64decode(data.split(',', 1)[1]))


def validate(client):
    code = """(() => {
        const a=Animation.all.find(a=>a.name==='00_Showcase'); a.select();
        const results=[];
        for (const t of [2.14,3.94,4.99]) {
            Timeline.setTime(t); Animator.preview();
            Project.model_3d.updateMatrixWorld(true);
            const chisel=Group.all.find(g=>g.name==='chisel');
            const mallet=Group.all.find(g=>g.name==='mallet');
            const cv=new THREE.Vector3(0,(12.98-9.2)*0.48,0);
            const mv=new THREE.Vector3(7*0.28,9*0.28,0);
            chisel.mesh.localToWorld(cv); mallet.mesh.localToWorld(mv);
            const tip=new THREE.Vector3(0,-9.2*0.48,0);
            chisel.mesh.localToWorld(tip);
            results.push({time:t,contact_error:cv.distanceTo(mv),tip:tip.toArray(),cap:cv.toArray(),mallet_face:mv.toArray()});
        }
        return JSON.stringify({checks:results,textureErrors:Texture.all.map(t=>({name:t.name,error:t.error})),
            clips:Animation.all.map(a=>({name:a.name,length:a.length,bones:Object.keys(a.animators).length}))});
    })()"""
    data = client.evaluate(code)
    (OUT / 'blockbench_validation.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
    print(json.dumps(data, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['load', 'setup', 'validate', 'snapshot', 'frames', 'play'])
    parser.add_argument('--time', type=float, default=1.9)
    args = parser.parse_args()
    client = Client()
    try:
        if args.command == 'load':
            load(client)
        elif args.command == 'setup':
            setup(client)
        elif args.command == 'validate':
            validate(client)
        elif args.command == 'snapshot':
            path = OUT / 'preview.png'
            capture(client, args.time, path)
            print(path)
        elif args.command == 'frames':
            folder = OUT / 'preview_frames'
            folder.mkdir(exist_ok=True)
            for i in range(148):
                capture(client, i / 20, folder / f'{i:04d}.png')
                if i % 20 == 0:
                    print(f'Captured {i + 1}/148', flush=True)
        elif args.command == 'play':
            setup(client)
            print(client.evaluate("(() => { Timeline.setTime(0); Animator.preview(); Timeline.start(); return {playing:Timeline.playing,clip:Animation.selected.name}; })()"))
    finally:
        client.close()


if __name__ == '__main__':
    main()
