# Writes Ovenlight's app icon as an Icon Composer document: an oven door with the light on,
# and three apps resting on the rack inside. The apps and rack are Liquid Glass; the light and
# the glow spilling through the glass are plain PNG layers with their softness baked in,
# because actool drops any SVG shape carrying a filter from the flattened renders (TestFlight,
# App Store, pre-iOS 26), though ictool shows it. Layers carry no painted highlights or
# shadows; the system supplies them, and Xcode renders every appearance and the pre-iOS 26
# images from this document. It also writes the website's icon.svg and icon-180.png, and
# Android's adaptive icon (a gradient background, the flat light, rack and apps in front, and
# a monochrome layer for themed icons) with the Play Store's 512 px icon.
#   uv run --with pillow --with numpy python3 generate.py   # rewrites ../../Ovenlight/Resources/AppIcon.icon, the site's and Android's icons
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
ANDROID = os.path.join(HERE, "../../android/app/src/main/res")
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

# Android: the same door, flat. The whole canvas sits at 92 percent of the 72 dp viewport,
# centered on the 108 dp layer, which keeps the window inside the 66 dp safe zone no
# launcher's mask clips.
LAYER, ART = 108, 0.92 * 72
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

def tile(box, radius, top, bottom):
    """A rounded box filled with a vertical gradient over its own height, on the canvas."""
    height = box[3] - box[1]
    ramp = np.array(rgb(top)) + (np.array(rgb(bottom)) - np.array(rgb(top))) * np.clip((np.arange(N) - box[1]) / height, 0, 1)[:, None]
    image = Image.fromarray(np.repeat(ramp[:, None, :], N, 1).astype(np.uint8)).convert("RGBA")
    image.putalpha(rounded(box, radius))
    return image

def app_boxes():
    return [(x0 + i * (APP + APP_GAP), RACK_Y + 4 - APP, x0 + i * (APP + APP_GAP) + APP, RACK_Y + 4) for i in range(3)]

def rack_box():
    return (WINDOW[0] + 52, RACK_Y, WINDOW[2] - 52, RACK_Y + RACK_H)

def flat_front():
    """The glow, the light, the rack and the apps, as one image on a clear canvas."""
    front = Image.new("RGBA", (N, N))
    for part in (spill(), light(), tile(rack_box(), RACK_H / 2, "#ffd27a", "#f4822c"),
                 *(tile(box, APP * 0.26, "#7a3210", "#4a1a06") for box in app_boxes())):
        front.alpha_composite(part)
    return front

def door(size):
    top, bottom = np.array(rgb(FILL[0])), np.array(rgb(FILL[1]))
    return Image.fromarray((top + (bottom - top) * np.linspace(0, 1, size)[:, None, None]).repeat(size, 1).astype(np.uint8)).convert("RGBA")

def dp(v):
    """A canvas coordinate on the 108 dp layer."""
    return (LAYER - ART) / 2 + v * ART / N

def rounded_path(box, radius):
    x1, y1, x2, y2 = (dp(v) for v in box)
    rr = radius * ART / N
    return (f"M{x1 + rr:.2f},{y1:.2f}H{x2 - rr:.2f}A{rr:.2f},{rr:.2f} 0,0 1 {x2:.2f},{y1 + rr:.2f}"
            f"V{y2 - rr:.2f}A{rr:.2f},{rr:.2f} 0,0 1 {x2 - rr:.2f},{y2:.2f}H{x1 + rr:.2f}"
            f"A{rr:.2f},{rr:.2f} 0,0 1 {x1:.2f},{y2 - rr:.2f}V{y1 + rr:.2f}A{rr:.2f},{rr:.2f} 0,0 1 {x1 + rr:.2f},{y1:.2f}Z")

def vector(paths):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Written by design/icon/generate.py. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{LAYER}dp" android:height="{LAYER}dp" android:viewportWidth="{LAYER}" android:viewportHeight="{LAYER}">\n'
            f'{paths}</vector>\n')

def android_icons():
    for name in ("drawable", "mipmap-anydpi"):
        os.makedirs(os.path.join(ANDROID, name), exist_ok=True)
    # The door's gradient, over the whole layer.
    with open(os.path.join(ANDROID, "drawable", "ic_launcher_background.xml"), "w") as f:
        f.write(vector(
            f'    <path android:pathData="M0,0h{LAYER}v{LAYER}h-{LAYER}z">\n'
            '        <aapt:attr name="android:fillColor">\n'
            f'            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="0" android:endY="{LAYER}"\n'
            f'                android:startColor="{FILL[0]}" android:endColor="{FILL[1]}" />\n'
            '        </aapt:attr>\n'
            '    </path>\n'))
    # Themed icons: the window solid, with the rack and the apps cut out of it.
    holes = "".join(rounded_path(box, APP * 0.26) for box in app_boxes()) + rounded_path(rack_box(), RACK_H / 2)
    with open(os.path.join(ANDROID, "drawable", "ic_launcher_monochrome.xml"), "w") as f:
        f.write(vector(f'    <path android:fillType="evenOdd" android:fillColor="#FFFFFFFF"\n'
                       f'        android:pathData="{rounded_path(WINDOW, WINDOW_RADIUS)}{holes}" />\n'))
    front = flat_front()
    for density, size in DENSITIES.items():
        folder = os.path.join(ANDROID, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        art = round(size * ART / LAYER)
        layer = Image.new("RGBA", (size, size))
        layer.alpha_composite(front.resize((art, art), Image.LANCZOS), ((size - art) // 2, (size - art) // 2))
        layer.save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)
    with open(os.path.join(ANDROID, "mipmap-anydpi", "ic_launcher.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!-- Written by design/icon/generate.py. -->\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
    # Google Play's icon: full bleed and square, since Play rounds and shadows it itself.
    play = door(N)
    play.alpha_composite(front)
    play.resize((512, 512), Image.LANCZOS).convert("RGBA").save(os.path.join(HERE, "play-icon-512.png"), optimize=True)

android_icons()
