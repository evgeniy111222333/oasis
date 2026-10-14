"""Deterministic, pixel-authored 128px tool atlases and native cube models."""
from pathlib import Path
from PIL import Image, ImageDraw
import random, json, uuid, base64, math, shutil

OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[1]
RES=ROOT/'fabric-server/client-mod-gradle/src/main/resources/assets/eclipseserver'
SLOTS={'wood':(0,0,32,48),'leather':(32,0,64,32),'brass':(64,0,80,16),
       'steel':(80,0,112,48),'edge':(112,0,120,32),'dark':(120,0,128,32),
       'blade':(32,32,64,64),'bowl':(0,64,32,96),'end':(64,48,80,64),
       'stitch':(80,48,84,52),'cap':(84,48,100,64)}

def atlas(name,seed):
    rng=random.Random(seed); im=Image.new('RGBA',(128,128),(41,33,28,255)); d=ImageDraw.Draw(im)
    def rgb(base,j):return tuple(max(0,min(255,int(c+j))) for c in base)+(255,)
    for mat,(x,y,x1,y1) in SLOTS.items():
        base={'wood':(104,70,42),'leather':(89,36,32),'brass':(155,124,59),
              'steel':(68,69,73),'edge':(175,176,179),'dark':(39,40,42),
              'blade':(79,81,83),'bowl':(57,58,57),'end':(85,56,34),
              'stitch':(191,166,110),'cap':(73,74,78)}[mat]
        d.rectangle((x,y,x1-1,y1-1),fill=rgb(base,0))
        if mat=='wood':
            for xx in range(x,x1,2):
                for yy in range(y,y1,3):
                    h=rng.randint(4,13); d.rectangle((xx,yy,min(xx+1,x1-1),min(yy+h,y1-1)),fill=rgb(base,rng.randint(-24,18)))
            for xx in [3,12,22,28]:
                yy=rng.randrange(5,30);d.line((xx,yy,xx,yy+9),fill=rgb(base,-28))
        else:
            step=2 if mat not in ('brass','edge','stitch') else 1
            for yy in range(y,y1,step):
                for xx in range(x,x1,step):
                    j=rng.randint(-15,15)
                    if mat=='blade':j+=max(0,(yy-y-20))*7
                    d.rectangle((xx,yy,min(x1-1,xx+step-1),min(y1-1,yy+step-1)),fill=rgb(base,j))
        if mat not in ('wood','dark','stitch'):
            d.line((x,y,x1-1,y),fill=rgb(base,38));d.line((x,y,x,y1-1),fill=rgb(base,22))
            d.line((x1-1,y,x1-1,y1-1),fill=rgb(base,-24));d.line((x,y1-1,x1-1,y1-1),fill=rgb(base,-25))
        if mat=='leather':
            for yy in range(3,30,6):
                for xx in (35,59):d.rectangle((xx,yy,xx,yy+2),fill=(185,153,105,255))
        if mat=='brass':
            d.line((65,2,77,2),fill=(205,177,108,255));d.rectangle((69,6,72,8),fill=(120,93,39,255))
    im.save(OUT/(name+'.png'))
    return im

def guid():return str(uuid.uuid4())

class Model:
    def __init__(self,name,grip,tip_up=False):self.name=name;self.grip=grip;self.tip_up=tip_up;self.elements=[];self.groups={}
    def cube(self,name,c,s,mat,group='tool',rotation=None,origin=None,uv=None):
        f=[round(c[i]-s[i]/2,5) for i in range(3)];t=[round(c[i]+s[i]/2,5) for i in range(3)]
        r=list(SLOTS[mat]); faces={}
        for face in ['north','south','east','west','up','down']:
            rr=list((uv or {}).get(face,r));faces[face]={'uv':rr,'texture':0}
        e={'name':name,'type':'cube','uuid':guid(),'from':f,'to':t,'origin':origin or list(c),
           'rotation':rotation or [0,0,0],'box_uv':False,'autouv':0,'faces':faces,'rescale':False,'color':0}
        self.elements.append(e);self.groups.setdefault(group,[]).append(e['uuid']);return e
    def oct(self,name,y,h,w,depth,mat,group='handle',b=.23,z=8):
        self.cube(name+' face',[8,y,z],[w,h,depth-2*b],mat,group)
        self.cube(name+' sides',[8,y,z],[w-2*b,h,depth],mat,group)
        for x in [-1,1]:
            for zz in [-1,1]:
                self.cube(name+' bevel',[8+x*(w/2-b),y,z+zz*(depth/2-b)],
                          [b*math.sqrt(2),h,b*math.sqrt(2)],mat,group,[0,45,0])
    def save(self,seed):
        atlas(self.name,seed); tex=OUT/(self.name+'.png')
        scale=.68; offset=(8-self.grip)*scale
        # Canonical handle axis Y; the grip lies at the item origin after this transform.
        display={
          'thirdperson_righthand':{'rotation':[0,0,0],'translation':[0,offset,0],'scale':[scale]*3},
          'thirdperson_lefthand':{'rotation':[0,0,0],'translation':[0,offset,0],'scale':[scale]*3},
          'firstperson_righthand':{'rotation':[0,-20,-18 if self.tip_up else 165],'translation':[1.1,1.6,.8],'scale':[.62]*3},
          'firstperson_lefthand':{'rotation':[0,20,18 if self.tip_up else -165],'translation':[1.1,1.6,.8],'scale':[.62]*3},
          'gui':{'rotation':[22,-35,-35 if self.tip_up else 145],'translation':[0,0,0],'scale':[.78]*3},
          'ground':{'rotation':[0,0,90],'translation':[0,2.6,0],'scale':[.48]*3},
          'fixed':{'rotation':[0,180,0],'translation':[0,0,0],'scale':[.78]*3},
          'head':{'rotation':[0,0,0],'translation':[0,7,0],'scale':[.5]*3}}
        groups=[];outliner=[]
        for name,children in self.groups.items():
            u=guid();groups.append({'name':name,'uuid':u,'origin':[8,self.grip,8],'rotation':[0,0,0]});outliner.append({'uuid':u,'children':children})
        texture={'name':self.name+'.png','id':'0','uuid':guid(),'width':128,'height':128,
                 'uv_width':128,'uv_height':128,'source':'data:image/png;base64,'+base64.b64encode(tex.read_bytes()).decode()}
        bb={'meta':{'format_version':'5.0','model_format':'java_block','box_uv':False},'name':self.name,
            'resolution':{'width':128,'height':128},'elements':self.elements,'groups':groups,'outliner':outliner,
            'textures':[texture],'display':display,'ambientocclusion':False,'front_gui_light':False}
        (OUT/(self.name+'.bbmodel')).write_text(json.dumps(bb,ensure_ascii=False),encoding='utf8')
        elements=[]
        for e in self.elements:
            j={'name':e['name'],'from':e['from'],'to':e['to'],
               'faces':{f:{'uv':[round(v/8,6) for v in data['uv']],'texture':'#0'} for f,data in e['faces'].items()}}
            for a,v in enumerate(e.get('rotation',[0,0,0])):
                if v:j['rotation']={'origin':e['origin'],'axis':'xyz'[a],'angle':v,'rescale':False}
            elements.append(j)
        runtime={'credit':'Eclipse RP — artisan reference tools','texture_size':[128,128],
                 'textures':{'0':'eclipseserver:item/'+self.name,'particle':'eclipseserver:item/'+self.name},
                 'elements':elements,'display':display,'gui_light':'side','ambientocclusion':False}
        (OUT/(self.name+'.item.json')).write_text(json.dumps(runtime,ensure_ascii=False,indent=2),encoding='utf8')
        for root in [RES,ROOT/'fabric-server/server/src/main/resources/assets/eclipseserver']:
            for folder in ['models/item','textures/item','items']:(root/folder).mkdir(parents=True,exist_ok=True)
            shutil.copy2(tex,root/'textures/item'/tex.name)
            (root/'textures/item'/(tex.name+'.mcmeta')).write_text('{"texture":{"blur":false,"clamp":false}}')
            (root/'models/item'/(self.name+'.json')).write_text(json.dumps(runtime,ensure_ascii=False,indent=2),encoding='utf8')
            (root/'items'/(self.name+'.json')).write_text(json.dumps({'model':{'type':'minecraft:model','model':'eclipseserver:item/'+self.name}}))
        print(self.name,len(elements),'cubes')

def handle(m,flat=False):
    m.oct('Walnut grip',12.25,7.4,2.55,2.15 if flat else 2.55,'wood')
    m.oct('Forged striking cap',16.3,.9,2.95,2.5 if flat else 2.95,'cap','fittings',.12)
    m.oct('Brass ferrule',8.55,1.15,2.65,2.25 if flat else 2.65,'brass','fittings',.12)
    for n in range(3):
        yy=9.65+n*.76
        m.oct('Oxblood leather wrap '+str(n),yy,.67,2.7,2.3 if flat else 2.7,'leather','wrap',.15)
        if flat:
            m.cube('Waxed saddle stitch',[8,yy,6.82],[.11,.2,.06],'stitch','stitches')
        else:
            for z in [6.62,9.38]:m.cube('Spiral leather overlap',[8,yy+.15,z],[2.55,.32,.12],'leather','wrap',[0,0,22.5 if n%2==0 else -22.5])

p=Model('carver_chisel_point',12)
handle(p)
for y,h,w in [(7.0,2.0,1.35),(5.35,1.3,.94),(4.2,1,.62),(3.42,.55,.38),(2.99,.3,.22),(2.73,.22,.11)]:
    p.oct('Tapered forged point',y,h,w,w,'steel' if y>4 else 'edge','blade',min(.1,w/5))
p.save(602)

f=Model('carver_chisel_flat',12)
handle(f,True)
f.cube('Tang',[8,7.25,8],[1.35,1.6,.65],'steel','blade')
f.cube('Blade shoulder',[8,6.25,8],[2.5,1,.66],'steel','blade')
for x,sign in [(6.82,-1),(9.18,1)]:f.cube('Sloping shoulder',[x,6.15,8],[1.8,.65,.66],'steel','blade',[0,0,sign*22.5])
f.cube('Wide blade',[8,4.45,8],[4.55,2.6,.66],'blade','blade')
f.cube('Honed bevel',[8,2.94,7.92],[4.7,.43,.43],'edge','blade',[22.5,0,0])
f.cube('Cutting edge',[8,2.68,7.93],[4.75,.12,.16],'edge','blade')
f.save(603)

g=Model('carver_fine_graver',6,True)
g.oct('Palm grip',6.1,6.3,3.35,3.1,'wood',b=.5)
for y,h,w in [(2.65,.6,3.1),(2.15,.4,2.65),(1.85,.2,2.15),(9.45,.4,3.0),(9.8,.3,2.6),(10.1,.3,2.15)]:g.oct('Shaped palm shoulder',y,h,w,w*.93,'wood',b=.3)
g.oct('Leather heel wrap',3.45,1.2,3.5,3.26,'leather','wrap',.5)
g.oct('Brass collar',10.65,.85,1.7,1.6,'brass','fittings',.18)
g.cube('Square steel shank',[8,12.6,8],[.65,3.1,.65],'steel','blade')
g.cube('Diamond cutting face',[8,14.8,8],[.96,1.6,.7],'steel','blade',[0,45,0])
for x,sign in [(7.78,-1),(8.22,1)]:
    g.cube('V cutting lip',[x,15.63,7.75],[.18,1.15,.23],'edge','blade',[0,0,sign*22.5])
g.cube('Back bevel',[8,15.47,8.2],[.68,.9,.28],'edge','blade',[22.5,0,0])
g.save(604)

s=Model('carver_material_scoop',12.2)
s.oct('Walnut bulb grip',12.7,5.7,3.1,2.8,'wood',b=.35,z=8.2)
for y,h,w in [(9.65,.4,2.6),(9.3,.3,2.3),(15.65,.4,2.7)]:s.oct('Tapered handle',y,h,w,w*.9,'wood',b=.25,z=8.2)
s.oct('Heel brass cap',16,.4,2.7,2.5,'brass','fittings',.23,z=8.2)
s.oct('Brass bolster',8.85,1,2.45,2.3,'brass','fittings',.18,z=8.2)
s.oct('Stitched leather grip',12.1,1.6,3.25,2.96,'leather','wrap',.35,z=8.2)
for yy in [11.6,12.1,12.6]:
    for x in [7.2,8.8]:s.cube('Saddle stitch',[x,yy,6.7],[.12,.22,.06],'stitch','stitches')
s.cube('Offset forged tang',[8,7.62,8.4],[1.1,1.7,.55],'steel','bowl',[22.5,0,0])
s.cube('Bowl floor',[8,4.53,8.93],[5.4,4.7,.4],'bowl','bowl')
for x in [5.23,10.77]:
    s.cube('Tray side wall',[x,4.55,8.2],[.3,4.9,1.85],'steel','bowl')
    s.cube('Burnished side rim',[x,4.55,7.25],[.32,4.9,.13],'edge','rims')
for y in [2.15,6.95]:
    s.cube('Tray end wall',[8,y,8.2],[5.25,.3,1.85],'steel','bowl')
    s.cube('Burnished end rim',[8,y,7.25],[5.55,.32,.13],'edge','rims')
s.save(605)

# The previously approved mallet gets the same grip convention and every item display context.
m=Model('carver_mallet',8,True)
old=json.loads((ROOT/'art/carver_mallet_reference_128/carver_mallet.bbmodel').read_text(encoding='utf8'))
for e in old['elements']:
    e=json.loads(json.dumps(e));e['from']=[v+a for v,a in zip(e['from'],[8,2,8])];e['to']=[v+a for v,a in zip(e['to'],[8,2,8])]
    e['origin']=[v+a for v,a in zip(e['origin'],[8,2,8])];m.elements.append(e);m.groups.setdefault('mallet',[]).append(e['uuid'])
m.save(601)
# Preserve the authored mallet atlas instead of substituting the chisel atlas.
original=ROOT/'art/carver_mallet_reference_128/carver_mallet.png'
shutil.copy2(original,OUT/'carver_mallet.png')
for root in [RES,ROOT/'fabric-server/server/src/main/resources/assets/eclipseserver']:shutil.copy2(original,root/'textures/item/carver_mallet.png')
path=OUT/'carver_mallet.bbmodel';bb=json.loads(path.read_text(encoding='utf8'));bb['textures'][0]['source']='data:image/png;base64,'+base64.b64encode(original.read_bytes()).decode();path.write_text(json.dumps(bb),encoding='utf8')
