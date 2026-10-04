# Writes Ovenlight's app icon as an Icon Composer document: an oven door with the light on,
# and three apps resting on the rack inside. The apps and rack are Liquid Glass; the light and
# the glow spilling through the glass are plain PNG layers with their softness baked in,
# because actool drops any SVG shape carrying a filter from the flattened renders (TestFlight,
# App Store, pre-iOS 26), though ictool shows it. Layers carry no painted highlights or
# shadows; the system supplies them, and Xcode renders every appearance and the pre-iOS 26
# images from this document. It also writes the website's icon.svg and icon-180.png.
#   uv run --with pillow --with numpy python3 generate.py   # rewrites ../../Ovenlight/Resources/AppIcon.icon and the site's icons
# Preview any rendition with ictool (Icon Composer.app/Contents/Executables):
#   ictool AppIcon.icon --export-image --output-file icon.png --platform iOS \
#     --rendition Default --width 1024 --height 1024 --scale 1   (Dark, TintedDark, ClearLight...)
import io
import json
import os
import shutil
import subprocess
import tempfile

import numpy as np
from PIL import Image, ImageCms, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "../../Ovenlight/Resources/AppIcon.icon")
SITE = os.path.join(HERE, "../../site")
N = 1024
FILL = ("#4a2213", "#170907")

# The oven window, landscape like an oven door's, and the parts inside it.
# Coordinates are on the 1024 canvas.
WINDOW, WINDOW_RADIUS = (104, 188, 920, 836), 150
RACK_Y, RACK_H = 680, 36
APP, APP_GAP = 144, 40

# The light falls from a lamp just behind the window's top edge, spreading wider than it falls.
# Along the way (0 at the lamp, 1 at its reach) it runs from white-hot to a deep ember, and
# thins so the door shows faintly through the ember.
HEIGHT = WINDOW[3] - WINDOW[1]
LAMP_X, LAMP_Y = 512, WINDOW[1] + 0.04 * HEIGHT
REACH, SPREAD = 0.92 * HEIGHT, 1.6
LIGHT = [(0, "#fff8e8"), (0.16, "#ffcc7a"), (0.44, "#f28c34"), (1, "#8a2c0a")]
SPILL = ("#ff9a40", 0.35, 60)   # the glow through the glass onto the door: color, opacity, blur

def fade(t):
    return 1 - 0.24 * t ** 1.6

def svg(defs, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{N}" height="{N}" viewBox="0 0 {N} {N}">'
            f'<defs>{defs}</defs>{body}</svg>\n')

def linear(id, top, bottom):
    return (f'<linearGradient id="{id}" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bottom}"/></linearGradient>')

def rgb(h):
    return [int(h[i:i + 2], 16) for i in (1, 3, 5)]

def rounded(box, radius):
    mask = Image.new("L", (N, N))
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=radius, fill=255)
    return mask

# Each part is its gradient and its shapes, drawn alone as a layer and together on the site.
x0 = 512 - (3 * APP + 2 * APP_GAP) / 2
apps = (linear("a", "#7a3210", "#4a1a06"), "".join(
    f'<rect x="{x0 + i * (APP + APP_GAP)}" y="{RACK_Y + 4 - APP}" width="{APP}" height="{APP}" rx="{APP * 0.26:.0f}" fill="url(#a)"/>'
    for i in range(3)))

rack = (linear("r", "#ffd27a", "#f4822c"),
        f'<rect x="{WINDOW[0] + 52}" y="{RACK_Y}" width="{WINDOW[2] - WINDOW[0] - 104}" height="{RACK_H}" rx="{RACK_H / 2}" fill="url(#r)"/>')

def light():
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    t = np.clip(np.sqrt(((xx - LAMP_X) / SPREAD) ** 2 + (yy - LAMP_Y) ** 2) / REACH, 0, 1)
    stops = np.array([rgb(color) for _, color in LIGHT], np.float32)
    color = np.stack([np.interp(t, [at for at, _ in LIGHT], stops[:, c]) for c in range(3)], -1)
    window = np.asarray(rounded(WINDOW, WINDOW_RADIUS).filter(ImageFilter.GaussianBlur(3)), np.float32) / 255
    alpha = fade(t) * window
    return Image.fromarray(np.dstack([color, alpha * 255]).astype(np.uint8), "RGBA")

def spill():
    color, opacity, blur = SPILL
    image = Image.new("RGBA", (N, N), (*rgb(color), 0))
    image.putalpha(rounded(WINDOW, WINDOW_RADIUS).filter(ImageFilter.GaussianBlur(blur)).point(lambda v: int(v * opacity)))
    return image

def site_svg():
    window = (f'x="{WINDOW[0]}" y="{WINDOW[1]}" width="{WINDOW[2] - WINDOW[0]}" height="{HEIGHT}" '
              f'rx="{WINDOW_RADIUS}"')
    glow = (f'<radialGradient id="w" gradientUnits="userSpaceOnUse" cx="{LAMP_X}" cy="{LAMP_Y:g}" r="{REACH:g}" '
            f'gradientTransform="translate({LAMP_X} {LAMP_Y:g}) scale({SPREAD} 1) translate({-LAMP_X} {-LAMP_Y:g})">'
            + "".join(f'<stop offset="{at}" stop-color="{color}" stop-opacity="{fade(at):.3f}"/>' for at, color in LIGHT)
            + "</radialGradient>")
    color, opacity, blur = SPILL
    soften = f'<filter id="s" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="{blur}"/></filter>'
    return svg(linear("bg", *FILL) + glow + soften + rack[0] + apps[0],
               f'<rect width="{N}" height="{N}" rx="230" fill="url(#bg)"/>'
               f'<rect {window} fill="{color}" opacity="{opacity}" filter="url(#s)"/>'
               f'<rect {window} fill="url(#w)"/>' + rack[1] + apps[1])

def touch_icon(size=180):
    """ictool's render on the door's color, so iOS can round the corners itself."""
    developer = subprocess.run(["xcode-select", "-p"], check=True, capture_output=True, text=True).stdout.strip()
    ictool = os.path.join(developer, "../Applications/Icon Composer.app/Contents/Executables/ictool")
    with tempfile.TemporaryDirectory() as tmp:
        png = os.path.join(tmp, "icon.png")
        subprocess.run([ictool, OUT, "--export-image", "--output-file", png, "--platform", "iOS", "--rendition", "Default",
                        "--width", str(size), "--height", str(size), "--scale", "1"], check=True, stdout=subprocess.DEVNULL)
        render = Image.open(png)
        # ictool writes Display P3; the site's PNG carries no profile, so browsers read it as sRGB.
        p3 = ImageCms.ImageCmsProfile(io.BytesIO(render.info["icc_profile"]))
        render = ImageCms.profileToProfile(render.convert("RGBA"), p3, ImageCms.createProfile("sRGB"), outputMode="RGBA")
        top, bottom = np.array(rgb(FILL[0])), np.array(rgb(FILL[1]))
        door = (top + (bottom - top) * np.linspace(0, 1, size)[:, None, None]).repeat(size, 1)
        image = Image.fromarray(door.astype(np.uint8)).convert("RGBA")
        image.alpha_composite(render)
        return image.convert("RGB")

def gradient(top, bottom):
    return {"linear-gradient": [top, bottom],
            "orientation": {"start": {"x": 0.5, "y": 0}, "stop": {"x": 0.5, "y": 1}}}

def srgb(h):
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (1, 3, 5))
    return f"srgb:{r:.5f},{g:.5f},{b:.5f},1.00000"

def group(name, *images, glass=True, shadow=None):
    return {"name": name,
            "layers": [{"name": image.split(".")[0].title(), "glass": glass, "image-name": image} for image in images],
            "shadow": shadow or {"kind": "neutral", "opacity": 0.5},
            "translucency": {"enabled": False, "value": 0.5}}

# Groups, like the layers in each, are listed front to back.
icon = {
    "fill-specializations": [
        {"value": gradient(srgb(FILL[0]), srgb(FILL[1]))},
        {"appearance": "dark", "value": gradient(srgb("#211310"), srgb("#070404"))},
    ],
    "groups": [
        group("Apps", "apps.svg"),
        group("Rack", "rack.svg"),
        group("Light", "light.png", "spill.png", glass=False, shadow={"kind": "none", "opacity": 0}),
    ],
    "supported-platforms": {"squares": "shared"},
}

shutil.rmtree(os.path.join(OUT, "Assets"), ignore_errors=True)
os.makedirs(os.path.join(OUT, "Assets"))
for name, part in {"apps.svg": apps, "rack.svg": rack}.items():
    with open(os.path.join(OUT, "Assets", name), "w") as f:
        f.write(svg(*part))
light().save(os.path.join(OUT, "Assets", "light.png"), optimize=True)
spill().save(os.path.join(OUT, "Assets", "spill.png"), optimize=True)
with open(os.path.join(OUT, "icon.json"), "w") as f:
    json.dump(icon, f, indent=2)
    f.write("\n")
with open(os.path.join(SITE, "icon.svg"), "w") as f:
    f.write(site_svg())
touch_icon().save(os.path.join(SITE, "icon-180.png"), optimize=True)
