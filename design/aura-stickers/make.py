# Builds AURA stickers (transparent, white die-cut outline) and AURA GIFs (animated cards)
# from the AURA emoji artwork. Output: out/stickers/<pack>/<nn>.webp, out/gifs/<nn>.gif, index.tsv
import os, math, json, random, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ART = '../figgen/cut'
FONT = 'font/Fredoka-Bold.ttf'
OUT = 'out'

def key(e):
    return '_'.join('%x' % ord(c) for c in e if ord(c) != 0xFE0F)

def art(e):
    a = Image.open(f'{ART}/{key(e)}.png').convert('RGBA')
    return a.crop(a.getchannel('A').getbbox())

def fit(im, size):
    s = size / max(im.size)
    return im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)

def font(px): return ImageFont.truetype(FONT, px)

# ------------------------------------------------------------------ stickers
def outline(img, width, color=(255, 255, 255, 255)):
    """Die-cut sticker: white border around the shape plus a soft shadow."""
    pad = width * 3
    W, H = img.width + pad * 2, img.height + pad * 2
    base = Image.new('RGBA', (W, H), (0, 0, 0, 0)); base.alpha_composite(img, (pad, pad))
    a = np.array(base.getchannel('A'))
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (width * 2 + 1, width * 2 + 1))
    m = cv2.dilate((a > 40).astype(np.uint8) * 255, k)
    m = cv2.GaussianBlur(m, (0, 0), 0.9)
    border = Image.new('RGBA', (W, H), color); border.putalpha(Image.fromarray(m))
    shadow = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    sh = Image.fromarray(m).filter(ImageFilter.GaussianBlur(width * 0.9))
    sh = Image.fromarray((np.array(sh) * 0.28).astype(np.uint8))
    shadow.putalpha(sh)
    out = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    out.alpha_composite(shadow, (0, max(2, width // 2)))
    out.alpha_composite(border); out.alpha_composite(base)
    return out.crop(out.getchannel('A').getbbox())

def caption_img(text, px, fill, stroke=(255, 255, 255, 255), sw=None):
    f = font(px); sw = sw or max(4, px // 6)
    d = ImageDraw.Draw(Image.new('RGBA', (1, 1)))
    l, t, r, b = d.textbbox((0, 0), text, font=f, stroke_width=sw)
    im = Image.new('RGBA', (r - l + 8, b - t + 8), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((4 - l, 4 - t), text, font=f, fill=fill, stroke_width=sw, stroke_fill=stroke)
    return im

def sticker(e, text=None, color=None, S=384):
    em = fit(art(e), 300 if not text else 250)
    if text:
        cap = caption_img(text, 64, color or (40, 40, 60, 255), sw=0)
        if cap.width > 330: cap = fit(cap, 330) if cap.width >= cap.height else cap
        canvas = Image.new('RGBA', (max(em.width, cap.width) + 20, em.height + cap.height - 6), (0, 0, 0, 0))
        canvas.alpha_composite(em, ((canvas.width - em.width) // 2, 0))
        # slight tilt on the caption, like a hand-placed label
        cap = cap.rotate(-4, resample=Image.BICUBIC, expand=True)
        canvas.alpha_composite(cap, ((canvas.width - cap.width) // 2, em.height - 22))
        em = canvas.crop(canvas.getchannel('A').getbbox())
    st = outline(em, 11)
    st = fit(st, S - 8) if max(st.size) > S - 8 else st
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    out.alpha_composite(st, ((S - st.width) // 2, (S - st.height) // 2))
    return out

PACKS = {
    'Smileys': '😀 😃 😄 😁 😆 😅 🤣 😂 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🫡 🤐 🤨 😐 😏 😒 🙄 😬 😮‍💨 😌 😔 😪 🤤 😴 😷 🤒 🥵 🥶 🥴 😵‍💫 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😲 🥺 🥹 😦 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖',
    'Love': '❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💖 💗 💓 💞 💕 💘 💝 ❤️‍🔥 ❤️‍🩹 💌 💋 🌹 💐 😻 🫶',
    'Hands': '👍 👎 👏 🙌 🙏 🤝 👋 🤙 ✌️ 🤞 🤟 🤘 👌 🤌 👊 ✊ 💪 🫡 🫵 ☝️ 👉 👈',
    'Animals': '🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🦄 🐝 🦋 🐢 🐙 🐬 🐳',
    'Food': '🍕 🍔 🍟 🌭 🌮 🍿 🍩 🍪 🎂 🍰 🧁 🍫 🍦 🍓 🍉 🍌 🥑 ☕ 🧋 🍹',
    'Party': '🎉 🎊 🥳 🎈 🎁 🎂 🍾 🥂 ✨ 🌟 ⭐ 🔥 💯 🏆 🥇 👑 💎 🚀 🌈 ☀️',
}
TEXT = [
    ('😂', 'LOL', (255, 120, 40)), ('😱', 'OMG', (230, 60, 90)), ('🙏', 'Thank you', (90, 110, 230)),
    ('🥰', 'Love you', (240, 70, 130)), ('☀️', 'Good morning', (245, 150, 20)), ('😴', 'Good night', (110, 90, 220)),
    ('🎉', 'Congrats!', (240, 80, 160)), ('🎂', 'Happy birthday', (240, 90, 120)), ('👍', 'Yes!', (40, 170, 90)),
    ('🙅', 'Nope', (230, 70, 70)), ('🥺', 'Sorry', (120, 120, 220)), ('🥹', 'Miss you', (220, 90, 160)),
    ('🏃', 'On my way', (30, 150, 220)), ('🤤', 'Hungry', (240, 130, 30)), ('😎', 'Cool', (30, 140, 220)),
    ('🤩', 'Wow', (245, 160, 20)), ('👋', 'Hi!', (40, 160, 220)), ('👋', 'Bye!', (150, 100, 230)),
    ('🤔', 'Hmm…', (130, 110, 90)), ('😅', 'Oops', (240, 110, 60)), ('💪', 'You got this', (230, 90, 40)),
    ('🤗', 'Hugs', (240, 120, 60)), ('😡', 'Grr', (220, 50, 50)), ('🫡', 'Got it', (60, 140, 90)),
    ('🙌', 'Yay!', (240, 150, 20)), ('😭', 'Noooo', (60, 130, 220)), ('🔥', 'Fire', (240, 90, 20)),
    ('💯', 'Facts', (230, 50, 60)), ('🥳', 'Party time', (180, 80, 230)), ('☕', 'Coffee?', (150, 90, 50)),
]

# ------------------------------------------------------------------ GIFs
PALETTES = [((255, 214, 224), (255, 236, 200)), ((214, 232, 255), (233, 222, 255)), ((210, 246, 230), (230, 248, 210)),
            ((255, 228, 205), (255, 210, 225)), ((226, 222, 255), (210, 240, 255)), ((255, 244, 200), (255, 222, 205))]

def card(W, pal, phase=0.0):
    a, b = np.array(pal[0], float), np.array(pal[1], float)
    y, x = np.mgrid[0:W, 0:W] / (W - 1)
    t = np.clip(0.5 * (x + y) + 0.08 * math.sin(phase * 2 * math.pi), 0, 1)[..., None]
    g = a * (1 - t) + b * t
    # soft glow behind the emoji
    r = np.hypot(x - 0.5, y - 0.45)
    g = g + (np.clip(0.35 - r, 0, 1) / 0.35)[..., None] * 28
    return Image.fromarray(np.clip(g, 0, 255).astype(np.uint8)).convert('RGBA')

def place(bg, im, cx, cy, scale=1.0, rot=0.0, sx=1.0, sy=1.0, alpha=1.0):
    w, h = max(1, int(im.width * scale * sx)), max(1, int(im.height * scale * sy))
    x = im.resize((w, h), Image.BICUBIC)
    if rot: x = x.rotate(rot, resample=Image.BICUBIC, expand=True)
    if alpha < 1: x.putalpha(Image.fromarray((np.array(x.getchannel('A')) * alpha).astype(np.uint8)))
    bg.alpha_composite(x, (int(cx - x.width / 2), int(cy - x.height / 2)))

def shadow(bg, cx, cy, w, k=1.0):
    sh = Image.new('RGBA', bg.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([cx - w / 2 * k, cy - 7 * k, cx + w / 2 * k, cy + 7 * k], fill=(60, 40, 80, 60))
    bg.alpha_composite(sh.filter(ImageFilter.GaussianBlur(5)))

def text_on(bg, text, color, y, t=None):
    cap = caption_img(text, 40, color + (255,), sw=7)
    if cap.width > bg.width - 24: cap = fit(cap, bg.width - 24)
    s = 1.0 if t is None else min(1.0, 0.6 + 0.4 * t)
    place(bg, cap, bg.width / 2, y, scale=s)

def ease_out_back(t):
    c = 1.7; t -= 1
    return t * t * ((c + 1) * t + c) + 1

N = 24
def anim(kind, e, text=None, color=(60, 60, 90), pal=None, W=256, extra=None):
    em = fit(art(e), 146 if text else 164)
    pal = pal or random.choice(PALETTES)
    cy0 = 110 if text else W / 2
    frames = []
    rnd = random.Random(hash(e + kind) & 0xffff)
    parts = [(rnd.random(), rnd.random(), rnd.random()) for _ in range(14)]
    BG = card(W, pal, 0)
    for i in range(N):
        t = i / N
        bg = BG.copy()
        s, rot, sx, sy, dx, dy, alpha = 1, 0, 1, 1, 0, 0, 1
        if kind == 'bounce':
            h = abs(math.sin(math.pi * t))
            dy = -38 * h
            sq = max(0, 1 - h * 4) if h < 0.25 else 0
            sx, sy = 1 + 0.12 * sq, 1 - 0.12 * sq
            shadow(bg, W / 2, cy0 + em.height / 2 + 6, em.width * 0.7, 1 - 0.4 * h)
        elif kind == 'beat':
            p = (t * 2) % 1
            s = 1 + 0.16 * math.exp(-((p - 0.1) ** 2) / 0.004) + 0.10 * math.exp(-((p - 0.32) ** 2) / 0.004)
        elif kind == 'wave':
            rot = 18 * math.sin(2 * math.pi * t * 2)
        elif kind == 'shake':
            dx = 7 * math.sin(2 * math.pi * t * 4); rot = 6 * math.sin(2 * math.pi * t * 4 + 1)
        elif kind == 'wobble':
            rot = 10 * math.sin(2 * math.pi * t); s = 1 + 0.04 * math.sin(4 * math.pi * t)
        elif kind == 'float':
            dy = 10 * math.sin(2 * math.pi * t); rot = 4 * math.sin(2 * math.pi * t + 0.5)
        elif kind == 'spin':
            sx = abs(math.cos(math.pi * t)) * 0.9 + 0.1
        elif kind == 'pop':
            p = min(1, t / 0.45)
            s = 0.15 + 0.85 * ease_out_back(p) if t < 0.45 else 1 + 0.03 * math.sin(2 * math.pi * (t - 0.45) * 2)
        elif kind == 'flicker':
            sy = 1 + 0.06 * math.sin(2 * math.pi * t * 3) + 0.03 * math.sin(2 * math.pi * t * 7)
            sx = 1 - 0.03 * math.sin(2 * math.pi * t * 3)
        # particles behind / around
        if extra in ('hearts', 'sparkles', 'confetti', 'zzz', 'tears', 'notes', 'stars'):
            for j, (a, b, c) in enumerate(parts):
                pt = (t + a) % 1
                x = 30 + b * (W - 60); y = W - pt * (W + 30)
                d = ImageDraw.Draw(bg)
                if extra == 'confetti':
                    col = [(255, 90, 120), (255, 200, 40), (80, 190, 255), (120, 220, 120), (190, 110, 255)][j % 5]
                    y = -10 + pt * (W + 20); x = x + 12 * math.sin(2 * math.pi * (pt * 2 + c))
                    ang = 360 * pt * (1 + c)
                    rect = Image.new('RGBA', (10, 5), col + (255,)).rotate(ang, expand=True)
                    bg.alpha_composite(rect, (int(x), int(y)))
                elif extra == 'hearts':
                    place(bg, fit(art('💗'), 18 + 14 * c), x, y, alpha=max(0, 1 - pt * 0.9))
                elif extra == 'sparkles' or extra == 'stars':
                    sz = 6 + 10 * abs(math.sin(math.pi * (pt * 2 + c)))
                    px, py = 30 + a * (W - 60), 30 + c * (W - 60)
                    d.polygon([(px, py - sz), (px + sz * 0.3, py - sz * 0.3), (px + sz, py), (px + sz * 0.3, py + sz * 0.3),
                               (px, py + sz), (px - sz * 0.3, py + sz * 0.3), (px - sz, py), (px - sz * 0.3, py - sz * 0.3)],
                              fill=(255, 255, 255, 230) if extra == 'sparkles' else (255, 210, 60, 255))
                elif extra == 'zzz' and j < 3:
                    pt = (t + j / 3) % 1
                    cap = caption_img('z', int(22 + 14 * pt), (120, 110, 230, 255), sw=3)
                    place(bg, cap, W * 0.68 + 30 * pt, W * 0.32 - 60 * pt, alpha=1 - pt)
                elif extra == 'tears' and j < 6:
                    side = -1 if j % 2 else 1
                    x = W / 2 + side * (40 + 40 * pt); y = cy0 - 10 + 90 * pt * pt - 30 * pt
                    d.ellipse([x - 5, y - 7, x + 5, y + 7], fill=(90, 170, 255, int(255 * (1 - pt))))
                elif extra == 'notes' and j < 5:
                    place(bg, fit(art('🎵'), 26), x, y, alpha=1 - pt)
        place(bg, em, W / 2 + dx, cy0 + dy, scale=s, rot=rot, sx=sx, sy=sy, alpha=alpha)
        if text: text_on(bg, text, color, W - 44)
        frames.append(bg.convert('RGB'))
    return frames

def save_gif(frames, path):
    import subprocess, tempfile
    with tempfile.TemporaryDirectory() as d:
        for i, f in enumerate(frames): f.save(f'{d}/{i:03d}.png')
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-framerate', '20', '-i', f'{d}/%03d.png', '-vf',
            'split[a][b];[a]palettegen=max_colors=200:stats_mode=full[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle',
            '-loop', '0', path], check=True)

def save_gif_pil(frames, path):
    # one shared palette keeps the colours steady between frames
    big = Image.new('RGB', (frames[0].width, frames[0].height * len(frames)))
    for i, f in enumerate(frames): big.paste(f, (0, i * f.height))
    pal = big.quantize(colors=255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    fr = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    fr[0].save(path, save_all=True, append_images=fr[1:], duration=50, loop=0, optimize=True, disposal=1)

GIFS = [
    ('😂', 'shake', 'LOL', (240, 110, 30), None), ('🤣', 'wobble', 'Dying', (240, 110, 30), None),
    ('😍', 'beat', None, None, 'hearts'), ('🥰', 'float', 'Love you', (230, 70, 130), 'hearts'),
    ('❤️', 'beat', None, None, None), ('💖', 'beat', 'Love', (230, 70, 130), 'sparkles'),
    ('👋', 'wave', 'Hi!', (40, 150, 220), None), ('👋', 'wave', 'Bye!', (140, 90, 230), None),
    ('👍', 'pop', 'Yes!', (40, 160, 90), None), ('👏', 'shake', 'Bravo', (230, 120, 40), 'sparkles'),
    ('🙏', 'float', 'Thank you', (90, 100, 230), 'sparkles'), ('🎉', 'bounce', 'Congrats!', (230, 70, 150), 'confetti'),
    ('🥳', 'bounce', 'Party!', (170, 80, 230), 'confetti'), ('🎂', 'pop', 'Happy birthday', (230, 80, 120), 'confetti'),
    ('😭', 'shake', 'Noooo', (60, 120, 220), 'tears'), ('😢', 'float', 'Sad', (80, 120, 210), 'tears'),
    ('😴', 'float', 'Good night', (110, 90, 220), 'zzz'), ('☀️', 'spin', 'Good morning', (240, 140, 20), 'stars'),
    ('😱', 'shake', 'OMG', (230, 60, 90), None), ('🤯', 'pop', 'Mind blown', (220, 80, 40), 'stars'),
    ('🔥', 'flicker', 'Fire', (240, 90, 20), None), ('💯', 'pop', None, None, 'sparkles'),
    ('😎', 'bounce', 'Cool', (30, 130, 220), None), ('🤩', 'beat', 'Wow', (240, 150, 20), 'stars'),
    ('🤔', 'wobble', 'Hmm…', (130, 110, 90), None), ('🙄', 'wobble', 'Whatever', (120, 110, 160), None),
    ('😡', 'shake', 'Grr', (220, 50, 50), None), ('😅', 'wobble', 'Oops', (240, 110, 60), None),
    ('🤗', 'beat', 'Hugs', (240, 120, 60), 'hearts'), ('😘', 'pop', 'Mwah', (230, 70, 130), 'hearts'),
    ('🫶', 'beat', None, None, 'hearts'), ('💪', 'bounce', 'You got this', (230, 90, 40), None),
    ('🚀', 'float', "Let's go", (60, 110, 230), 'stars'), ('🌟', 'spin', None, None, 'sparkles'),
    ('🐶', 'bounce', None, None, None), ('🐱', 'wobble', None, None, None),
    ('🎵', 'bounce', None, None, 'notes'), ('🍕', 'wobble', 'Pizza?', (220, 100, 40), None),
    ('☕', 'float', 'Coffee?', (150, 90, 50), None), ('🏃', 'bounce', 'On my way', (30, 150, 220), None),
    ('🙈', 'shake', None, None, None), ('🤫', 'float', 'Shh', (110, 90, 200), None),
    ('🥺', 'float', 'Please', (120, 110, 220), 'sparkles'), ('😇', 'float', None, None, 'stars'),
    ('🤝', 'pop', 'Deal', (60, 140, 90), None), ('✨', 'beat', None, None, 'sparkles'),
    ('👑', 'float', 'Queen', (220, 160, 20), 'sparkles'), ('🎈', 'float', None, None, None),
]

if __name__ == '__main__':
    random.seed(7)
    os.makedirs(f'{OUT}/stickers', exist_ok=True); os.makedirs(f'{OUT}/gifs', exist_ok=True)
    rows = []
    for pack, s in (PACKS.items() if not os.environ.get('GIFONLY') else []):
        d = f'{OUT}/stickers/{pack.lower()}'; os.makedirs(d, exist_ok=True)
        for i, e in enumerate(s.split()):
            if not os.path.exists(f'{ART}/{key(e)}.png'): print('skip', e); continue
            sticker(e).save(f'{d}/{i:02d}.webp', 'WEBP', quality=88, method=6)
            rows.append(('sticker', pack, f'stickers/{pack.lower()}/{i:02d}.webp', e))
    d = f'{OUT}/stickers/words'; os.makedirs(d, exist_ok=True)
    for i, (e, t, c) in enumerate(TEXT if not os.environ.get('GIFONLY') else []):
        sticker(e, t, c + (255,)).save(f'{d}/{i:02d}.webp', 'WEBP', quality=88, method=6)
        rows.append(('sticker', 'Words', f'stickers/words/{i:02d}.webp', f'{e} {t}'))
    only = sys.argv[1:]
    for i, (e, kind, text, color, extra) in enumerate(GIFS):
        if only and str(i) not in only: continue
        save_gif(anim(kind, e, text, color or (60, 60, 90), PALETTES[i % len(PALETTES)], extra=extra), f'{OUT}/gifs/{i:02d}.gif')
        rows.append(('gif', 'GIFs', f'gifs/{i:02d}.gif', f'{e} {text or ""}'.strip()))
    with open(f'{OUT}/index.tsv', 'w') as f:
        for r in rows: f.write('\t'.join(r) + '\n')
    print('stickers', sum(1 for r in rows if r[0] == 'sticker'), 'gifs', sum(1 for r in rows if r[0] == 'gif'))
