# Writes Ovenlight's app icon as an Icon Composer document: the oven light on, seen through
# the door. A lit window glows warm from a capsule lamp shaped like the Dynamic Island, with
# three apps resting on the rack inside. The lamp, apps and rack are Liquid Glass; the light
# and the glow spilling through the glass are plain PNG layers with their softness baked in,
# because actool drops any SVG shape carrying a filter from the flattened renders
# (TestFlight, App Store, pre-iOS 26), though ictool shows it. Layers carry no painted
# highlights or shadows; the system supplies them, and Xcode renders every appearance and
# the pre-iOS 26 images from this document.
#   uv run --with pillow --with numpy python3 generate.py   # rewrites ../../Ovenlight/Resources/AppIcon.icon
# Preview any rendition with ictool (Icon Composer.app/Contents/Executables):
#   ictool AppIcon.icon --export-image --output-file icon.png --platform iOS \
#     --rendition Default --width 1024 --height 1024 --scale 1   (Dark, TintedDark, ClearLight...)
import json
import os
import shutil

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../Ovenlight/Resources/AppIcon.icon")
N = 1024

# The oven window, landscape, and the parts inside it. Coordinates are on the 1024 canvas.
WINDOW, WINDOW_RADIUS = (104, 232, 920, 800), 150
LAMP_W, LAMP_H, LAMP_TOP = 236, 66, 266          # a capsule just under the window's top edge
RACK_Y, RACK_H = 664, 36
APP, APP_GAP = 144, 40

def svg(defs, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{N}" height="{N}" viewBox="0 0 {N} {N}">'
            f'<defs>{defs}</defs>{body}</svg>\n')

def linear(id, top, bottom):
    return (f'<linearGradient id="{id}" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bottom}"/></linearGradient>')

def rounded(box, radius):
    mask = Image.new("L", (N, N))
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=radius, fill=255)
    return mask

lamp = svg(linear("l", "#fffaf0", "#ffdca8"),
           f'<rect x="{512 - LAMP_W / 2}" y="{LAMP_TOP}" width="{LAMP_W}" height="{LAMP_H}" rx="{LAMP_H / 2}" fill="url(#l)"/>')

x0 = 512 - (3 * APP + 2 * APP_GAP) / 2
apps = svg(linear("a", "#7a3210", "#4a1a06"), "".join(
    f'<rect x="{x0 + i * (APP + APP_GAP)}" y="{RACK_Y + 4 - APP}" width="{APP}" height="{APP}" rx="{APP * 0.26:.0f}" fill="url(#a)"/>'
    for i in range(3)))

rack = svg(linear("r", "#ffd27a", "#f4822c"),
           f'<rect x="{WINDOW[0] + 52}" y="{RACK_Y}" width="{WINDOW[2] - WINDOW[0] - 104}" height="{RACK_H}" rx="{RACK_H / 2}" fill="url(#r)"/>')

def light():
    """The window's light: warm white at the lamp, amber, then ember toward the corners."""
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    lx, ly = 512, LAMP_TOP + LAMP_H / 2 + 10
    t = np.clip(np.sqrt(((xx - lx) / 1.25) ** 2 + (yy - ly) ** 2) / 504, 0, 1)
    stops = np.array([[0xff, 0xf6, 0xdc], [0xff, 0xb8, 0x55], [0xdc, 0x64, 0x1e]], np.float32)
    rgb = np.stack([np.interp(t, [0, 0.5, 1], stops[:, c]) for c in range(3)], -1)
    window = np.asarray(rounded(WINDOW, WINDOW_RADIUS).filter(ImageFilter.GaussianBlur(3)), np.float32) / 255
    alpha = (1 - 0.66 * t ** 1.2) * window
    return Image.fromarray(np.dstack([rgb, alpha * 255]).astype(np.uint8), "RGBA")

def spill():
    """A faint glow through the glass onto the door around the window."""
    image = Image.new("RGBA", (N, N), (0xff, 0x9a, 0x40, 0))
    image.putalpha(rounded(WINDOW, WINDOW_RADIUS).filter(ImageFilter.GaussianBlur(60)).point(lambda v: int(v * 0.35)))
    return image

def gradient(top, bottom):
    return {"linear-gradient": [top, bottom],
            "orientation": {"start": {"x": 0.5, "y": 0}, "stop": {"x": 0.5, "y": 1}}}

def srgb(h):
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (1, 3, 5))
    return f"srgb:{r:.5f},{g:.5f},{b:.5f},1.00000"

# Icon Composer allows four groups, so the light and its spill share one.
def group(name, *images, glass=True, shadow=None):
    return {"name": name,
            "layers": [{"name": image.split(".")[0].title(), "glass": glass, "image-name": image} for image in images],
            "shadow": shadow or {"kind": "neutral", "opacity": 0.5},
            "translucency": {"enabled": False, "value": 0.5}}

# Groups, like the layers in each, are listed front to back.
icon = {
    "fill-specializations": [
        {"value": gradient(srgb("#4a2213"), srgb("#170907"))},
        {"appearance": "dark", "value": gradient(srgb("#211310"), srgb("#070404"))},
    ],
    "groups": [
        group("Lamp", "lamp.svg", shadow={"kind": "layer-color", "opacity": 0.6}),
        group("Apps", "apps.svg"),
        group("Rack", "rack.svg"),
        group("Light", "light.png", "spill.png", glass=False, shadow={"kind": "none", "opacity": 0}),
    ],
    "supported-platforms": {"squares": "shared"},
}

shutil.rmtree(os.path.join(OUT, "Assets"), ignore_errors=True)
os.makedirs(os.path.join(OUT, "Assets"))
for name, data in {"lamp.svg": lamp, "apps.svg": apps, "rack.svg": rack}.items():
    with open(os.path.join(OUT, "Assets", name), "w") as f:
        f.write(data)
light().save(os.path.join(OUT, "Assets", "light.png"), optimize=True)
spill().save(os.path.join(OUT, "Assets", "spill.png"), optimize=True)
with open(os.path.join(OUT, "icon.json"), "w") as f:
    json.dump(icon, f, indent=2)
    f.write("\n")
