"""Render the menu bar template image and the app icon from the logo in
design/mac.html. Needs Playwright (Chromium) and iconutil:

    python mac/scripts/icons.py   # writes mac/Resources/{Tray*.png,AppIcon.icns}
"""
import asyncio, os, re, shutil, subprocess, tempfile
from playwright.async_api import async_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
html = open(os.path.join(ROOT, 'design/mac.html'), encoding='utf-8').read()
defs = re.search(r'(<symbol id="logo".*?</symbol>).*?(<mask id="tray-cut".*?</mask>)\s*(<symbol id="tray".*?</symbol>)', html, re.S)
LOGO, MASK, TRAY = defs.groups()
OUT = os.path.join(ROOT, 'mac/Resources')

def page(body, w, h):
    return f'''<html><body style="margin:0;background:transparent">
<svg width="0" height="0" style="position:absolute"><defs>{MASK}</defs>{LOGO}{TRAY}</svg>
<div id="c" style="width:{w}px;height:{h}px">{body}</div></body></html>'''

# macOS app icon: 1024 canvas, 824 rounded square (Apple's grid), paper colour, logo ~56% of the square.
def app_icon(size):
    s = size / 1024
    return page(f'''<svg width="{size}" height="{size}" viewBox="0 0 1024 1024">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fffef8"/><stop offset="1" stop-color="#fbf3dc"/></linearGradient>
<filter id="sh" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="10" stdDeviation="14" flood-color="#000" flood-opacity=".22"/></filter></defs>
<rect x="100" y="100" width="824" height="824" rx="185" fill="url(#g)" filter="url(#sh)"/>
<rect x="100.5" y="100.5" width="823" height="823" rx="185" fill="none" stroke="#000" stroke-opacity=".06"/>
<svg x="282" y="295" width="460" height="434" viewBox="0 1 398 383"><use href="#logo"/></svg></svg>''', size, size)

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page()
        async def shot(html_, path, w, h):
            await pg.set_viewport_size({'width': w, 'height': h})
            await pg.set_content(html_)
            await (await pg.query_selector('#c')).screenshot(path=path, omit_background=True)
        # Template image: black on transparent; 19×18 pt like other status items.
        for scale, name in ((1, 'TrayTemplate.png'), (2, 'TrayTemplate@2x.png')):
            w, h = 19 * scale, 18 * scale
            await shot(page(f'<svg width="{w}" height="{h}" style="color:#000"><use href="#tray"/></svg>', w, h), os.path.join(OUT, name), w, h)
        tmp = tempfile.mkdtemp()
        iconset = os.path.join(tmp, 'AppIcon.iconset')
        os.mkdir(iconset)
        for pt in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                px = pt * scale
                suffix = '' if scale == 1 else '@2x'
                await shot(app_icon(px), os.path.join(iconset, f'icon_{pt}x{pt}{suffix}.png'), px, px)
        await b.close()
    subprocess.run(['iconutil', '-c', 'icns', iconset, '-o', os.path.join(OUT, 'AppIcon.icns')], check=True)
    shutil.copy(os.path.join(iconset, 'icon_512x512@2x.png'), os.path.join(ROOT, '.tmp/shots/mac-appicon.png')) if os.path.isdir(os.path.join(ROOT, '.tmp/shots')) else None
    shutil.rmtree(tmp)

asyncio.run(main())
