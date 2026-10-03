# Writes the link preview image, site/og-invite.jpg (1200 x 630), by rendering og-invite.html
# in headless Chrome and saving it as a JPEG.
#   uv run --with pillow python3 generate.py      # rewrites ../../site/og-invite.jpg
import os
import subprocess
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "../../site/og-invite.jpg")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

with tempfile.TemporaryDirectory() as tmp:
    png = os.path.join(tmp, "og.png")
    subprocess.run([CHROME, "--headless=new", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--window-size=1200,630", f"--screenshot={png}",
                    "file://" + os.path.join(HERE, "og-invite.html")],
                   check=True, capture_output=True)
    Image.open(png).convert("RGB").save(OUT, "JPEG", quality=90, optimize=True)
