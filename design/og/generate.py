# Writes the link preview images (1200 x 630), site/og.jpg from og.html and site/og-invite.jpg
# from og.html?invite, by rendering each at 2x in Playwright's headless Chromium and saving it
# downsampled as a JPEG.
#   uv run --with pillow python3 generate.py      # rewrites both
# Needs Playwright's browsers (npx playwright install chromium-headless-shell) and macOS,
# for the New York font.
import glob
import os
import subprocess
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SITE = os.path.join(HERE, "../../site")
SHELLS = sorted(glob.glob(os.path.expanduser(
    "~/Library/Caches/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-mac-*/chrome-headless-shell")))
if not SHELLS:
    raise SystemExit("no Playwright headless shell: npx playwright install chromium-headless-shell")

with tempfile.TemporaryDirectory() as tmp:
    for name, query in [("og.jpg", ""), ("og-invite.jpg", "?invite")]:
        png = os.path.join(tmp, "og.png")
        subprocess.run([SHELLS[-1], "--headless", "--hide-scrollbars", "--force-device-scale-factor=2",
                        "--window-size=1200,630", f"--screenshot={png}",
                        "file://" + os.path.join(HERE, "og.html") + query],
                       check=True, capture_output=True)
        img = Image.open(png).convert("RGB")
        assert img.size == (2400, 1260), img.size
        img.resize((1200, 630), Image.LANCZOS).save(os.path.join(SITE, name), "JPEG", quality=90, optimize=True)
