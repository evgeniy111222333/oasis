"""Encode native Blockbench frames and assemble a visual inspection sheet."""
from pathlib import Path
import subprocess
import shutil
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent
FFMPEG = shutil.which('ffmpeg')
assert FFMPEG, 'ffmpeg is required'


def encode(folder, output, filters, encoding):
    subprocess.run([FFMPEG, '-hide_banner', '-loglevel', 'error', '-y',
                    '-framerate', '20', '-i', str(OUT / folder / '%04d.png'),
                    '-filter_complex', filters, '-map', '[out]', *encoding,
                    str(OUT / output)], check=True)


background='color=c=0x182326:s=960x720:r=20[bg];[bg][0:v]overlay=shortest=1:format=auto'
encode('preview_frames', 'preview.mp4', background+',format=yuv420p[out]',
       ['-c:v','h264_mf','-b:v','3M','-an'])
encode('work_frames', 'work_loop.gif',
       background+',scale=800:600:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:reserve_transparent=0[p];[b][p]paletteuse=dither=bayer:bayer_scale=3[out]',
       ['-loop','0'])
sheet=Image.new('RGB',(1440,1152),(24,35,38))
draw=ImageDraw.Draw(sheet)
for i,t in enumerate([0,.65,1.08,2.1,3.55,5.22,6.6,7.8,9.2,10.4,12.03,14.6]):
    frame=Image.open(OUT/'preview_frames'/f'{round(t*20):04d}.png').convert('RGBA')
    bg=Image.new('RGBA',frame.size,(24,35,38,255)); bg.alpha_composite(frame)
    x,y=i%3*480,i//3*288
    sheet.paste(bg.convert('RGB').resize((480,270)),(x,y))
    draw.text((x+12,y+271),f'{t:.2f} s',fill=(210,221,215))
sheet.save(OUT/'contact_sheet.png')
with Image.open(OUT/'work_loop.gif') as gif:
    assert gif.n_frames==84, gif.n_frames
    duration=0
    for i in range(gif.n_frames):
        gif.seek(i); duration+=gif.info.get('duration',0)
    assert duration==4200,duration
subprocess.run([FFMPEG,'-hide_banner','-loglevel','error','-i',str(OUT/'preview.mp4'),'-f','null','-'],check=True)
print('Verified: 84-frame GIF, 4.2 s; full-scene video decodes successfully.')
