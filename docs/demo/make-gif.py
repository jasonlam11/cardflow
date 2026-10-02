"""Assembles docs/demo/frames/*.png into docs/images/demo.gif (Pillow)."""

from pathlib import Path

from PIL import Image

HERE = Path(__file__).parent
OUT = HERE.parent / "images" / "demo.gif"
# Seconds each frame stays on screen (overview, flagged charge, note, approved, card, question, answer, benefits answer)
HOLD = [2.0, 3.0, 1.2, 2.0, 2.5, 1.0, 2.5, 3.0]
WIDTH = 960

frames = []
for path in sorted((HERE / "frames").glob("*.png")):
    img = Image.open(path).convert("RGB")
    img = img.resize((WIDTH, round(img.height * WIDTH / img.width)), Image.LANCZOS)
    frames.append(img.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE))
durations = [int(1000 * HOLD[i] if i < len(HOLD) else 2000) for i in range(len(frames))]
frames[0].save(OUT, save_all=True, append_images=frames[1:], duration=durations, loop=0, optimize=True)
print(f"wrote {OUT} ({len(frames)} frames, {OUT.stat().st_size / 1e6:.2f} MB)")
