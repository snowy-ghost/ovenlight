# ovenlight.app

The Ovenlight website: the home, support, privacy and invite (`/join`) pages, as a
static site served by Cloudflare (Workers static assets, configured in the repo root's
`wrangler.jsonc`, which also attaches the ovenlight.app domain). Deploy from the repo root:

    npx wrangler deploy

`.assetsignore` keeps this README off the site. `llms.txt` tells coding agents what
Ovenlight is and how to use it, served as `text/plain`; it points to `ovenlight guide`
rather than repeating docs/building-apps.md.

The home, support and privacy pages share `style.css`; the join page keeps its styles
inline, since its Content-Security-Policy loads nothing but `join.js` and same-origin
images. Screenshots in `images/` come in WebP with a JPEG fallback, at 480 and 960 px wide. The link
preview image, `og-invite.jpg`, is written by `design/og/generate.py`.

`.well-known/apple-app-site-association` makes invite links open the app. iOS
accepts it only when it is served directly, with no redirect, as
`application/json`; `_headers` sets the content type.

`.well-known/security.txt` says where to report a security problem (RFC 9116);
`_headers` adds the charset the RFC requires. Renew its `Expires` date before it passes.
