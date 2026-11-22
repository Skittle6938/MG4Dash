"""Convert the existing splashFrames test's ARGB stream into a shareable GIF."""
import struct
from pathlib import Path
from PIL import Image

source = Path('app/build/screenshots/splash.rgb')
frames = []
with source.open('rb') as stream:
    width, height, count, delay = struct.unpack('>4i', stream.read(16))
    for _ in range(count):
        pixels = stream.read(width * height * 4)
        if len(pixels) != width * height * 4:
            raise SystemExit('Incomplete startup animation frames')
        frames.append(Image.frombytes('RGBA', (width, height), pixels, 'raw', 'ARGB').convert('RGB'))
frames[0].save(source.with_suffix('.gif'), save_all=True, append_images=frames[1:], duration=delay * 10, loop=0)
print(source.with_suffix('.gif'))
