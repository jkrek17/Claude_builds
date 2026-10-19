from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from PIL import Image
import json, math, subprocess, shutil
ROOT=Path(__file__).resolve().parents[1]
ID=ROOT/'identity'; SITE=ROOT/'site'; AS=SITE/'assets'
G='#252A31'; PAPER='#F7F3EC'; Y='#E9A17E'; SAGE='#E2E5E4'; INK='#252A31'; MUTED='#60666C'
FONT='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'; SERIF='/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf'
for p in [ID,AS,ID/'android/res/drawable',ID/'android/res/values',ID/'android/res/mipmap-anydpi-v26',ID/'android/res/mipmap-anydpi-v33',ID/'previews'] :p.mkdir(parents=True,exist_ok=True)
font=TTFont(FONT); gs=font.getGlyphSet(); cmap=font.getBestCmap(); upm=font['head'].unitsPerEm

def text(s,x,y,size,color=G,tracking=-0.3):
    pen=SVGPathPen(gs); xx=x
    for c in s:
        name=cmap[ord(c)]; scale=size/upm
        gs[name].draw(TransformPen(pen,(scale,0,0,-scale,xx,y)))
        xx += gs[name].width*scale+tracking
    return f'<path fill="{color}" d="{pen.getCommands()}"/>',xx-x

def svg(w,h,body,title='SceneRight'):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-label="{title}"><title>{title}</title>{body}</svg>'

def ringpath(cx,cy,r,ir):
    return f'M {cx-r},{cy} a {r},{r} 0 1,0 {2*r},0 a {r},{r} 0 1,0 {-2*r},0 M {cx-ir},{cy} a {ir},{ir} 0 1,0 {2*ir},0 a {ir},{ir} 0 1,0 {-2*ir},0'

def mark(x=0,y=0,size=100,color=G,light=Y):
    # One lens-like eye, a pupil, and a single catchlight. Gap gives the glint breathing room.
    pupil='M36 50a14 14 0 1 0 28 0a14 14 0 1 0 -28 0 M53 43a4 4 0 1 0 8 0a4 4 0 1 0 -8 0'
    dot=f'<circle cx="57" cy="43" r="4" fill="{light}"/>' if color!=light else ''
    return f'<g transform="translate({x} {y}) scale({size/100})"><path fill="{color}" fill-rule="evenodd" d="{ringpath(50,50,36,28)}"/><path fill="{color}" fill-rule="evenodd" d="{pupil}"/>{dot}</g>'

def lockup(x,y,scale=1,color=G,light=Y,descriptor=True):
    t,_=text('sceneright',108,77,80,color,-2.0)
    d,_=text('photo',119,103,17,color,2.4)
    return f'<g transform="translate({x} {y}) scale({scale})">{mark(0,0,100,color,light)}{t}{d if descriptor else ""}</g>'

for name,color,light in [('primary',G,Y),('reverse',PAPER,Y),('mono',G,G),('white','#FFFFFF','#FFFFFF')]:
    (ID/f'mark-{name}.svg').write_text(svg(100,100,mark(color=color,light=light)))
    (ID/f'logo-{name}.svg').write_text(svg(540,116,lockup(0,0,1,color,light)))
    body,_=text('sceneright',6,87,100,color,-3.0)
    (ID/f'wordmark-{name}.svg').write_text(svg(530,112,body))
# Standalone wordmark and mark are outlined, so no installed font is needed.
icon=svg(512,512,f'<rect width="512" height="512" fill="{Y}"/>'+mark(0,0,512,G,G))
(ID/'app-icon.svg').write_text(icon)
foreground=svg(108,108,mark(17,17,74,G,G))
(ID/'adaptive-foreground.svg').write_text(foreground)
(ID/'adaptive-background.svg').write_text(svg(108,108,f'<rect width="108" height="108" fill="{Y}"/>'))
(ID/'adaptive-monochrome.svg').write_text(svg(108,108,mark(17,17,74,'#000000','#000000')))
# Android layers use a 108 dp canvas; outer artwork lies within a 54 dp safe circle.
outer=ringpath(54,54,26.64,20.72)
vector=lambda paths:f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">{paths}</vector>\n'
circle=lambda cx,cy,r:f'M{cx-r},{cy}a{r},{r} 0,1 0,{2*r},0a{r},{r} 0,1 0,{-2*r},0'
for name,mono in [('ic_launcher_foreground',False),('ic_launcher_monochrome',True)]:
    col='#000000' if mono else G
    paths=f'<path android:fillColor="{col}" android:fillType="evenOdd" android:pathData="{outer}"/><path android:fillColor="{col}" android:fillType="evenOdd" android:pathData="{circle(54,54,10.36)} {circle(59.18,48.82,2.96)}"/>'
    (ID/f'android/res/drawable/{name}.xml').write_text(vector(paths))
(ID/'android/res/values/colors.xml').write_text(f'<resources><color name="sceneright_icon_background">{Y}</color><color name="sceneright_camera_background">#15171B</color><color name="sceneright_surface">#24282E</color><color name="sceneright_on_surface">#F2F3F4</color><color name="sceneright_secondary_text">#B7BDC4</color><color name="sceneright_primary_action">{Y}</color><color name="sceneright_on_primary_action">{G}</color></resources>\n')
for api in [26,33]:
    extra='\n  <monochrome android:drawable="@drawable/ic_launcher_monochrome" />' if api==33 else ''
    (ID/f'android/res/mipmap-anydpi-v{api}/ic_launcher.xml').write_text(f'<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n  <background android:drawable="@color/sceneright_icon_background" />\n  <foreground android:drawable="@drawable/ic_launcher_foreground" />{extra}\n</adaptive-icon>\n')

scene='''<defs><linearGradient id="sky" x2="0" y2="1"><stop stop-color="#D6DED2"/><stop offset="1" stop-color="#F2DDB0"/></linearGradient><linearGradient id="field" x2="0" y2="1"><stop stop-color="#B4BD8D"/><stop offset="1" stop-color="#5C8067"/></linearGradient></defs><rect width="600" height="800" fill="url(#sky)"/><circle cx="420" cy="180" r="43" fill="#F6EDCC"/><path d="M0 405Q160 250 340 355T600 330V800H0Z" fill="#9FAF9F"/><path d="M0 470Q170 340 355 410T600 400V800H0Z" fill="#78947E"/><path d="M0 495Q190 475 330 465T600 500V800H0Z" fill="url(#field)"/><path d="M250 800Q410 625 370 490Q415 630 335 800Z" fill="#DDD4A7"/><path d="M180 529L185 375L201 373L198 530Z" fill="#415D46"/><path d="M190 280C100 280 92 365 115 400C91 458 165 467 194 438C237 472 292 423 269 386C295 331 248 287 216 303C211 285 200 280 190 280Z" fill="#365E49"/><path d="M124 367Q138 305 190 311Q237 310 245 352Q200 335 184 370Q144 350 124 367Z" fill="#557954"/><g fill="#EBC66B"><circle cx="75" cy="698" r="4"/><circle cx="116" cy="751" r="5"/><circle cx="467" cy="679" r="4"/><circle cx="510" cy="723" r="5"/><circle cx="548" cy="646" r="4"/></g>'''
(AS/'meadow.svg').write_text(svg(600,800,scene,'Illustrated meadow used in a concept camera preview'))
# Assets for the site live inside its own folder.
for name in ['logo-primary.svg','logo-reverse.svg','mark-primary.svg','mark-reverse.svg','app-icon.svg']:
    shutil.copy(ID/name,AS/name)
for source,name in [(FONT,'SceneRightSans.woff'),(SERIF,'SceneRightSerif.woff')]:
    f=TTFont(source); f.flavor='woff';f.save(AS/name)
shutil.copy('/usr/share/doc/fonts-dejavu-core/copyright',AS/'FONT-LICENSE.txt')
# Feature graphic: all lettering is outlined for portable rendering.
feature=f'<rect width="1024" height="500" fill="{PAPER}"/>'
feature+=lockup(64,52,.67)
for line,yy in [('The photo you',254),('meant to take.',322)]:feature+=text(line,64,yy,54,G,-2)[0]
feature+=text('Frames for you. Shows you why.',68,395,18,MUTED,0)[0]
feature+=f'<rect x="698" y="46" width="260" height="408" rx="130" fill="{Y}"/>'+mark(700,123,255,G,G)
feature+=f'<path d="M730 382H926" stroke="{G}" stroke-width="2"/>'
(ID/'play-feature-graphic.svg').write_text(svg(1024,500,feature,'SceneRight — The photo you meant to take.'))
# Deliberately designed presentation board, not a browser screenshot.
board=f'<rect width="1600" height="1200" fill="{PAPER}"/>'
board+=text('SCENERIGHT / IDENTITY 03',64,58,15,MUTED,2)[0]+text('A little guidance. More keepers.',1065,58,15,MUTED,0)[0]
board+=f'<path d="M64 86H1536" stroke="#CFD6C8"/>'
board+=lockup(84,190,1.5)
board+=text('The photo you',94,521,67,G,-2)[0]+text('meant to take.',94,602,67,G,-2)[0]
board+=text('Frames for you. Shows you why.',100,665,24,MUTED,0)[0]
# Right panel: app icon and mark construction.
board+=f'<rect x="940" y="133" width="596" height="580" rx="28" fill="{Y}"/>'
board+=mark(1065,201,350,G,G)
board+=text('One quiet cue. Everything settles.',1019,652,22,G,-.4)[0]
board+=f'<path d="M64 762H1536" stroke="#CFD6C8"/>'
board+=text('01  /  THE PALETTE',64,812,14,MUTED,2)[0]
colors=[('INK',G),('PAPER',PAPER),('APRICOT',Y),('MIST',SAGE)]
for i,(name,col) in enumerate(colors):
    x=64+i*206
    board+=f'<rect x="{x}" y="844" width="186" height="132" rx="10" fill="{col}" stroke="#CBD1C4"/>'
    board+=text(name,x,1009,12,G,1)[0]+text(col,x,1038,16,MUTED,.1)[0]
board+=text('02  /  THE VOICE',980,812,14,MUTED,2)[0]
board+=text('Slightly right',980,889,39,G,-1)[0]
board+=text('Level',980,949,39,G,-1)[0]
board+=text('Try next time: move a little closer.',980,1006,20,MUTED,-.4)[0]
board+=f'<path d="M64 1095H1536" stroke="#CFD6C8"/>'
board+=text('Calm in the hand. Clear at a glance.',64,1145,19,G,0)[0]
board+=text('Name direction and visual identity / October 2026',1030,1145,14,MUTED,0)[0]
(ID/'brand-board.svg').write_text(svg(1600,1200,board,'SceneRight brand identity'))
# SVG render exports via Inkscape. No font dependencies in the logo or feature art.
for filename,width in [('brand-board',1600),('logo-primary',1480),('logo-reverse',1480),('mark-primary',1024),('mark-mono',1024),('app-icon',512),('play-feature-graphic',1024)]:
    subprocess.run(['inkscape',str(ID/f'{filename}.svg'),'--export-type=png',f'--export-width={width}',f'--export-filename={ID/f"{filename}.png"}'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
Image.open(ID/'play-feature-graphic.png').convert('RGB').save(ID/'play-feature-graphic.png')
# Play requires a 32-bit 512px PNG; no pre-rounded corners or baked-in shadow.
Image.open(ID/'app-icon.png').convert('RGBA').save(ID/'app-icon.png')
for density,px in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    folder=ID/f'android/res/mipmap-{density}';folder.mkdir(exist_ok=True)
    Image.open(ID/'app-icon.png').resize((px,px),Image.Resampling.LANCZOS).save(folder/'ic_launcher.png')
# Launcher mask previews and a small-size readability sheet.
canvas=Image.new('RGB',(1000,320),PAPER)
from PIL import ImageDraw,ImageFont
D=ImageDraw.Draw(canvas)
for i,shape in enumerate(['circle','rounded square','square']):
    base=Image.open(ID/'app-icon.png').convert('RGBA').resize((180,180))
    mask=Image.new('L',(180,180));d=ImageDraw.Draw(mask)
    if i==0:d.ellipse((0,0,180,180),fill=255)
    elif i==1:d.rounded_rectangle((0,0,180,180),radius=44,fill=255)
    else:d.rectangle((0,0,180,180),fill=255)
    canvas.paste(base,(70+330*i,40),mask)
    D.text((70+330*i,250),shape,font=ImageFont.truetype(FONT,18),fill=G)
canvas.save(ID/'previews/launcher-masks.png')
# Mark mono render uses a single ink; on transparency, reverse assets remain separate.
tokens={'color':{'ink':G,'paper':PAPER,'apricot':Y,'mist':SAGE,'text':INK,'secondaryText':MUTED},'type':{'body':'DejaVu Sans','display':'DejaVu Serif','wordmark':'Outlined DejaVu Sans with custom spacing'},'radius':{'control':12,'card':24},'space':[4,8,12,16,24,32,48,64,96],'motion':{'durationMs':180,'respectReducedMotion':True}}
tokens['app']={'cameraBackground':'#15171B','surface':'#24282E','onSurface':'#F2F3F4','secondaryText':'#B7BDC4','primaryAction':Y,'onPrimaryAction':G,'iconBackground':Y,'iconForeground':G}
tokens['statusColorPolicy']='Keep existing validated ready (green) and warning (amber) app tokens; no replacement production values supplied.'
(ID/'tokens.json').write_text(json.dumps(tokens,indent=2)+'\n')
print('Identity assets generated.')
