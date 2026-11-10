"""Compose the 512px store/release icon from the adaptive launcher layers."""
from PIL import Image

BG = 'app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png'
FG = 'app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png'
OUT = '../../tools/mob/nazarick_icon_512.png'

S = 512
bg = Image.open(BG).convert('RGBA').resize((S, S), Image.LANCZOS)
# Adaptive safe zone: content within central 66/108 of the canvas.
fg = Image.open(FG).convert('RGBA')
k = S * (66.0 / 108.0) / 432.0
nfg = fg.resize((int(432 * k), int(432 * k)), Image.LANCZOS)
canvas = bg.copy()
canvas.alpha_composite(nfg, ((S - nfg.width) // 2, (S - nfg.height) // 2))
canvas.convert('RGB').save(OUT, 'PNG')
print('wrote', OUT, canvas.size)

# also check the dashboard source size for reference
import os
p = r'C:\Users\magic_new.BETOS-AIO.000\.qwenpaw\workspaces\default\dashboard\static\ainz_wave.png'
print('dashboard ainz_wave:', Image.open(p).size, os.path.getsize(p), 'bytes')
