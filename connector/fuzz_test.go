package main

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"hash/crc32"
	"image"
	"image/png"
	"net/url"
	"strings"
	"testing"
	"unicode"
	"unicode/utf8"
)

// Fuzz targets for what reaches the connector from guests and apps. `go test` runs them
// on their seeds; `go test -fuzz FuzzName` searches further.

func hasControl(s string, newlines bool) bool {
	return strings.ContainsFunc(s, func(r rune) bool {
		return unicode.IsControl(r) && !(newlines && (r == '\n' || r == '\t'))
	})
}

// A guest's feedback, once accepted, holds no control characters and fits the limits.
func FuzzValidateFeedback(f *testing.F) {
	f.Add("a note", "https://coach.example.ts.net/", "")
	f.Add("line\x00\x1b[31m\nnext", "javascript:alert(1)", "iVBORw0KGgo=")
	f.Fuzz(func(t *testing.T, note, page, shot string) {
		got, png, err := validateFeedback(feedbackRequest{Note: note, PageURL: page, Screenshot: shot})
		if err != nil {
			return
		}
		if hasControl(got, true) || utf8.RuneCountInString(got) > maxNote || len(png) > maxScreenshot {
			t.Errorf("accepted note %q, %d-byte screenshot", got, len(png))
		}
	})
}

// A request's path reaches the log bounded and without control characters.
func FuzzLogPath(f *testing.F) {
	f.Add("/index.html")
	f.Add("/\x1b[2J\r\n" + strings.Repeat("é", 300))
	f.Fuzz(func(t *testing.T, path string) {
		got := logPath(path)
		if len(got) > maxLogPath+len("...") || hasControl(got, false) {
			t.Errorf("logPath(%q) = %q", path, got)
		}
	})
}

// Whatever page an app serves, reading it doesn't fail.
func FuzzParsePage(f *testing.F) {
	f.Add([]byte(`<html><head><link rel="manifest" href="/m.json"><meta name="theme-color" content="#fff"></head></html>`))
	f.Add([]byte(`<link rel=icon href=//evil.example/x.png sizes=999999999999x1>`))
	f.Fuzz(func(t *testing.T, page []byte) {
		parsePage(bytes.NewReader(page))
	})
}

// A link an app's manifest gives is used only when it stays on the app's own origin.
func FuzzSameOriginPath(f *testing.F) {
	for _, ref := range []string{"/icon.png", "icon.png?v=2", "//evil.example/x.png", "https://evil.example/", "javascript:alert(1)", "/a/../../b"} {
		f.Add(ref)
	}
	base, _ := url.Parse("https://coach.example.ts.net/app/")
	f.Fuzz(func(t *testing.T, ref string) {
		p, ok := sameOriginPath(base, ref)
		if !ok {
			return
		}
		u, err := base.Parse(p)
		if err != nil || !strings.HasPrefix(p, "/") || u.Host != base.Host || u.Scheme != base.Scheme {
			t.Errorf("sameOriginPath(%q) = %q, which leaves the origin", ref, p)
		}
	})
}

// A slug made from any name is a usable DNS label, or empty.
func FuzzSlugify(f *testing.F) {
	f.Add("Interview Coach")
	f.Add("  Zoë's  App!! " + strings.Repeat("x", 80))
	f.Fuzz(func(t *testing.T, name string) {
		if s := Slugify(name); s != "" && !slugPattern.MatchString(s) {
			t.Errorf("Slugify(%q) = %q", name, s)
		}
	})
}

// A 16-bit screenshot, which decodes to twice the memory, may have half the pixels.
func TestDeepScreenshotIsBounded(t *testing.T) {
	header := func(w, h int, m image.Image) string {
		var buf bytes.Buffer
		png.Encode(&buf, m)
		b := buf.Bytes()
		binary.BigEndian.PutUint32(b[16:], uint32(w)) // IHDR's width and height, then its CRC
		binary.BigEndian.PutUint32(b[20:], uint32(h))
		binary.BigEndian.PutUint32(b[29:], crc32.ChecksumIEEE(b[12:29]))
		return base64.StdEncoding.EncodeToString(b)
	}
	deep := image.NewNRGBA64(image.Rect(0, 0, 1, 1))
	if _, _, err := validateFeedback(feedbackRequest{Screenshot: header(4096, 4096, deep)}); err == nil || !strings.Contains(err.Error(), "dimensions") {
		t.Errorf("a 4096x4096 16-bit screenshot: %v", err)
	}
	flat := image.NewNRGBA(image.Rect(0, 0, 1, 1))
	if _, _, err := validateFeedback(feedbackRequest{Screenshot: header(4096, 4096, flat)}); err == nil || strings.Contains(err.Error(), "dimensions") {
		t.Errorf("a 4096x4096 8-bit screenshot should pass the size check: %v", err)
	}
}
