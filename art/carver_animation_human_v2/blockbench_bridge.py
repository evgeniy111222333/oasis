"""Load, inspect and render the separate human-motion study in Blockbench."""
from pathlib import Path
import argparse
import base64
import json
import math
import sys

OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(OUT.parent / 'carver_animation_first_cut'))
from blockbench_bridge import Client

NAME = 'Eclipse_Carver_Human_V2'


def load(client):
    path = str(OUT / 'eclipse_carver_human_v2.bbmodel')
    code = """(async () => await new Promise(resolve => {
        Blockbench.read([PATH], {errorbox:false}, files => {
            Codecs.project.load(JSON.parse(files[0].content), files[0]);
            Project.name='Eclipse_Carver_Human_V2'; Canvas.updateAll();
            resolve({name:Project.name,cubes:Cube.all.length,bones:Group.all.length,
                clips:Animation.all.map(a=>({name:a.name,length:a.length}))});
        });
    }))()""".replace('PATH', json.dumps(path))
    print(json.dumps(client.evaluate(code)), flush=True)
    client.tool('set_mode', {'mode_id': 'animate'})


def capture(client, time, path, clip='00_Showcase_Human', orbit=True):
    angle = math.pi * min(1, max(0, (time - 5.8) / 4.6)) if orbit else 0
    u = angle / math.pi
    angle = math.pi * u * u * (3 - 2 * u)
    x = 48 * math.cos(angle) - 34 * math.sin(angle)
    z = -48 * math.sin(angle) - 34 * math.cos(angle) - 12
    code = """(async () => {
        if (Project.name !== 'Eclipse_Carver_Human_V2') throw new Error('Wrong project');
        if(Timeline.playing) Timeline.pause();
        const a=Animation.all.find(a=>a.name===CLIP); if(Animation.selected!==a) a.select();
        Timeline.setTime(TIME); Animator.preview();
        const p=Preview.all.find(p=>p.id==='main'); p.setProjectionMode(false);
        p.camera.position.set(X,35,Z); p.controls.target.set(0,15,-12); p.controls.update(); p.render();
        return await new Promise(resolve=>p.screenshot({width:960,height:720},resolve));
    })()""".replace('CLIP', json.dumps(clip)).replace('TIME', str(time)).replace('X', str(x)).replace('Z', str(z))
    data = client.evaluate(code)
    if not isinstance(data, str) or not data.startswith('data:image/'):
        raise RuntimeError(str(data)[:180])
    path.write_bytes(base64.b64decode(data.split(',', 1)[1]))


def validate(client):
    expected = json.loads((OUT / 'timing_and_validation.json').read_text())['contact_checks']
    code = """(() => {
        if(Project.name!=='Eclipse_Carver_Human_V2') throw new Error('Wrong project');
        if(Timeline.playing) Timeline.pause();
        const checks=EXPECTED.map(c=>{
            Animation.all.find(a=>a.name===c.clip).select(); Timeline.setTime(c.time); Animator.preview();
            Project.model_3d.updateMatrixWorld(true);
            const ch=Group.all.find(g=>g.name==='chisel').mesh;
            const ma=Group.all.find(g=>g.name==='mallet').mesh;
            const cap=ch.localToWorld(new THREE.Vector3(0,(12.98-9.2)*.48,0));
            const face=ma.localToWorld(new THREE.Vector3(7*.28,9*.28,0));
            const tip=ch.localToWorld(new THREE.Vector3(0,-9.2*.48,0));
            return {clip:c.clip,time:c.time,contact_error:cap.distanceTo(face),
                target_error:tip.distanceTo(new THREE.Vector3(...c.tip)),tip:tip.toArray()};
        });
        const feet=[]; const a=Animation.all.find(a=>a.name==='00_Showcase_Human'); a.select();
        for(const t of [6.55,6.58,7.25,7.28,8.65,8.68]){
            Timeline.setTime(t); Animator.preview(); Project.model_3d.updateMatrixWorld(true);
            feet.push({time:t,left:Group.all.find(g=>g.name==='left_foot').mesh.getWorldPosition(new THREE.Vector3()).toArray(),
                right:Group.all.find(g=>g.name==='right_foot').mesh.getWorldPosition(new THREE.Vector3()).toArray()});
        }
        return {checks,feet,textureErrors:Texture.all.map(t=>({name:t.name,error:t.error})),
            clips:Animation.all.map(a=>({name:a.name,length:a.length}))};
    })()""".replace('EXPECTED', json.dumps(expected))
    data = client.evaluate(code)
    (OUT / 'blockbench_validation.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
    assert max(c['contact_error'] for c in data['checks']) < .002, data
    assert max(c['target_error'] for c in data['checks']) < .003, data
    assert all(not t['error'] for t in data['textureErrors']), data
    for a,b in zip(data['feet'][::2],data['feet'][1::2]):
        drift=math.dist(a['right'],b['right'])
        assert drift < .003, f"Planted foot slides: {drift}"
    print(json.dumps({'checks':len(data['checks']), 'max_contact_error':max(c['contact_error'] for c in data['checks']),
                      'max_target_error':max(c['target_error'] for c in data['checks']), 'feet':data['feet']}, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['load', 'validate', 'snapshots', 'frames', 'play'])
    args = parser.parse_args()
    client = Client()
    try:
        if args.command == 'load':
            load(client)
        elif args.command == 'validate':
            validate(client)
        elif args.command == 'snapshots':
            for label,t in [('entry',1.08),('transfer',3.5),('walk',7.8),('opposite',12.03)]:
                capture(client,t,OUT / (label+'.png'))
            capture(client,2.63,OUT / 'vertical.png','06_Work_Vertical_Face',False)
            print('Five inspection frames captured.')
        elif args.command == 'frames':
            for clip,count,folder,orbit in [('00_Showcase_Human',300,'preview_frames',True),('02_Work_Varied',84,'work_frames',False)]:
                target=OUT/folder; target.mkdir(exist_ok=True)
                for i in range(count):
                    capture(client,i/20,target/f'{i:04d}.png',clip,orbit)
                    if i%40==0: print(f'{folder}: {i+1}/{count}',flush=True)
        elif args.command == 'play':
            capture(client,.1,OUT / 'preview.png','02_Work_Varied',False)
            print(client.evaluate("(() => { Timeline.setTime(0); Animator.preview(); Timeline.start(); return {playing:Timeline.playing,clip:Animation.selected.name}; })()"))
    finally:
        client.close()


if __name__ == '__main__':
    main()
