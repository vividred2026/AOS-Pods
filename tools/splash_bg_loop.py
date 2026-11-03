"""S-01 스플래시 배경 루프 영상 생성기 → app/src/main/res/raw/splash_bg_loop.mp4

보라 #6B00DE + 가운데 은은한 글로우 + 떠다니는 음표(크기 랜덤). 16초, 끝 프레임이 첫 프레임과 이어져 반복 재생해도 끊김 없음.
가장자리는 정확히 #6B00DE(비네트 없음) — 상태바·내비바 뒤 창 배경과 이어지도록.

  python3 tools/splash_bg_loop.py out.mp4      # 렌더 (ffmpeg, numpy, Pillow, DejaVuSans 필요)
  python3 tools/splash_bg_loop.py stills       # 미리보기 PNG 4장
"""
import sys, math, os, subprocess
import numpy as np
from PIL import Image, ImageFont, ImageDraw

W, H, FPS, T = 1080, 1920, 30, 16.0   # T = loop length (s), must be seamless
BG = np.array([0x6B, 0x00, 0xDE], np.float32)

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
r = np.sqrt((xx - W / 2) ** 2 + (yy - H / 2) ** 2) / 700
glow = np.clip(1 - r, 0, 1) ** 2
bg_base = BG + glow[..., None] * np.array([40, 30, 33], np.float32)
vign = np.clip(np.sqrt((xx - W/2)**2 + (yy - H/2)**2) / 1300, 0, 1) ** 2
# no vignette: edges must equal window bg #6B00DE so status/nav bar areas blend

FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
_cache = {}
def sprite(ch, size):
    k = (ch, size)
    if k not in _cache:
        f = ImageFont.truetype(FONT, size)
        im = Image.new('L', (int(size * 1.4), int(size * 1.4)), 0)
        ImageDraw.Draw(im).text((size * 0.2, 0), ch, fill=255, font=f)
        _cache[k] = im.crop(im.getbbox())
    return _cache[k]

rng = np.random.default_rng(7)
TWO_PI = 2 * math.pi
NOTES = []
for i in range(18):
    speed = rng.uniform(140, 200)            # px/s upward
    NOTES.append(dict(
        ch=rng.choice(['♪', '♫', '♩', '♬']),
        size=int(40 + 200 * rng.random() ** 1.6),
        x=rng.uniform(80, W - 80),
        cycle=speed * T,                      # distance travelled per loop
        y0=rng.uniform(0, speed * T),
        sway=rng.uniform(15, 40),
        k_sway=int(rng.integers(8, 16)),      # integer cycles per loop -> seamless
        k_rot=int(rng.integers(5, 11)),
        k_bob=int(rng.integers(12, 22)),
        phase=rng.uniform(0, TWO_PI),
        rot=rng.uniform(-18, 18),
        op=rng.uniform(0.18, 0.45),
    ))

def frame(t):
    img = bg_base.copy()
    # slow breathing glow, 2 cycles per loop
    g = 0.25 + 0.25 * math.sin(TWO_PI * 2 * t / T)
    img += (glow ** 3)[..., None] * np.array([60, 50, 50], np.float32) * g
    for n in NOTES:
        # y wraps over its own cycle; spans from below screen to above screen
        y = H + 200 - ((n['y0'] + n['cycle'] * t / T) % n['cycle'])
        if y < -250 or y > H + 250:
            continue
        x = n['x'] + n['sway'] * math.sin(TWO_PI * n['k_sway'] * t / T + n['phase'])
        ang = n['rot'] + 10 * math.sin(TWO_PI * n['k_rot'] * t / T + n['phase'])
        bob = 1 + 0.06 * math.sin(TWO_PI * n['k_bob'] * t / T + n['phase'])
        spr = sprite(n['ch'], int(n['size'] * bob)).rotate(ang, resample=Image.BICUBIC, expand=True)
        m = np.asarray(spr, np.float32) / 255 * n['op']
        h, w = m.shape
        x0, y0 = int(x - w / 2), int(y - h / 2)
        xa, ya, xb, yb = max(0, x0), max(0, y0), min(W, x0 + w), min(H, y0 + h)
        if xa >= xb or ya >= yb:
            continue
        mm = m[ya - y0:yb - y0, xa - x0:xb - x0][..., None]
        img[ya:yb, xa:xb] = img[ya:yb, xa:xb] * (1 - mm) + 255 * mm
    return np.clip(img, 0, 255).astype(np.uint8)

if __name__ == '__main__':
    out = sys.argv[1]
    if out == 'stills':
        for t in [0, 4, 8, 12]:
            Image.fromarray(frame(t)).save(f'splash_bg_{t}.png')
        sys.exit()
    tmp = out[:-4] + '.part.mp4'
    pr = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                           '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-', '-c:v', 'libx264', '-preset', 'veryfast',
                           '-crf', '22', '-pix_fmt', 'yuv420p', '-g', '60', '-movflags', '+faststart', tmp],
                          stdin=subprocess.PIPE)
    for i in range(int(T * FPS)):          # last frame == frame(T) == frame(0) is omitted
        pr.stdin.write(frame(i / FPS).tobytes())
    pr.stdin.close()
    if pr.wait() == 0:
        os.replace(tmp, out)
