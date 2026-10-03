package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image/png"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
)

const (
	maxScreenshot    = 5 << 20   // decoded PNG
	maxNote          = 4000      // characters
	maxFeedbackStore = 500 << 20 // the inbox keeps the newest feedback up to this many bytes
	maxFeedbackItems = 1000      // and this many items
	feedbackPerHour  = 30        // per person
	maxFeedbackBody  = maxScreenshot*4/3 + 64<<10
	// A small PNG can decode to a huge image, so its size is capped before decoding.
	maxScreenshotSide   = 8192
	maxScreenshotPixels = 16 << 20
)

// feedbackReadTimeout bounds the upload of one feedback body. It is set per request: a
// server-wide ReadTimeout would also cut off WebSockets and streams. A var for tests.
var feedbackReadTimeout = 2 * time.Minute

// feedbackRequest is what Ovenlight posts to /__ovenlight/feedback.
type feedbackRequest struct {
	Note       string `json:"note"`
	PageURL    string `json:"pageUrl"`
	Screenshot string `json:"screenshotPngBase64"`
}

// tooLarge marks a validation error about size, answered with codeTooLarge.
type tooLarge struct{ error }

// validateFeedback checks a request and returns the trimmed note and the decoded PNG
// (nil when there is none).
func validateFeedback(req feedbackRequest) (string, []byte, error) {
	if !utf8.ValidString(req.Note) {
		return "", nil, errors.New("the note isn't valid UTF-8")
	}
	note := strings.TrimSpace(stripControl(req.Note, true))
	if utf8.RuneCountInString(note) > maxNote {
		return "", nil, tooLarge{fmt.Errorf("the note is longer than %d characters", maxNote)}
	}
	if len(req.PageURL) > 2048 {
		return "", nil, tooLarge{errors.New("the page URL is too long")}
	}
	if req.Screenshot == "" {
		if note == "" {
			return "", nil, errors.New("send a note, a screenshot or both")
		}
		return note, nil, nil
	}
	if base64.StdEncoding.DecodedLen(len(req.Screenshot)) > maxScreenshot+3 {
		return "", nil, tooLarge{fmt.Errorf("the screenshot is larger than %d MB", maxScreenshot>>20)}
	}
	img, err := base64.StdEncoding.DecodeString(req.Screenshot)
	if err != nil {
		return "", nil, errors.New("the screenshot isn't valid base64")
	}
	if len(img) > maxScreenshot {
		return "", nil, tooLarge{fmt.Errorf("the screenshot is larger than %d MB", maxScreenshot>>20)}
	}
	cfg, err := png.DecodeConfig(bytes.NewReader(img))
	if err != nil {
		return "", nil, errors.New("the screenshot must be a PNG")
	}
	if cfg.Width > maxScreenshotSide || cfg.Height > maxScreenshotSide || cfg.Width*cfg.Height > maxScreenshotPixels {
		return "", nil, errors.New("the screenshot's dimensions are implausible")
	}
	// Decoding all of it rejects a valid header followed by anything else.
	r := bytes.NewReader(img)
	if _, err := png.Decode(r); err != nil || r.Len() != 0 {
		return "", nil, errors.New("the screenshot isn't a whole PNG")
	}
	return note, img, nil
}

// stripControl removes control characters, which a terminal would act on, keeping
// newlines and tabs when multiline.
func stripControl(s string, multiline bool) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsControl(r) && !(multiline && (r == '\n' || r == '\t')) {
			return -1
		}
		return r
	}, s)
}

// feedbackLimiter allows each person a few notes per hour, and one upload at a time, so
// a guest can't fill the owner's disk or hold the connector with slow uploads.
type feedbackLimiter struct {
	mu   sync.Mutex
	sent map[string][]time.Time
	busy map[string]bool
}

var limiter = &feedbackLimiter{sent: map[string][]time.Time{}}

func (l *feedbackLimiter) allow(who string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	recent := l.sent[who][:0]
	for _, t := range l.sent[who] {
		if now.Sub(t) < time.Hour {
			recent = append(recent, t)
		}
	}
	if len(recent) >= feedbackPerHour {
		l.sent[who] = recent
		return false
	}
	l.sent[who] = append(recent, now)
	return true
}

// begin takes the person's upload slot; done gives it back.
func (l *feedbackLimiter) begin(who string) (done func(), ok bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.busy[who] {
		return nil, false
	}
	if l.busy == nil {
		l.busy = map[string]bool{}
	}
	l.busy[who] = true
	return func() {
		l.mu.Lock()
		defer l.mu.Unlock()
		delete(l.busy, who)
	}, true
}

// makeRoomLocked drops feedback until fb fits: its sender's oldest item first, so one
// person filling the inbox pushes out only their own, and the oldest overall once the
// sender has none. It returns the dropped items, whose screenshots the caller deletes.
// The caller holds sh.mu.
func (st *sharingState) makeRoomLocked(fb Feedback) []Feedback {
	total := fb.size()
	for _, f := range st.Feedback {
		total += f.size()
	}
	var dropped []Feedback
	for len(st.Feedback) > 0 && (len(st.Feedback) >= maxFeedbackItems || total > maxFeedbackStore) {
		// Items are appended in order, so the oldest come first.
		i := max(0, slices.IndexFunc(st.Feedback, func(f Feedback) bool { return f.UserID == fb.UserID }))
		dropped = append(dropped, st.Feedback[i])
		total -= st.Feedback[i].size()
		st.Feedback = slices.Delete(st.Feedback, i, i+1)
	}
	return dropped
}

func (f Feedback) size() int { return f.ScreenshotBytes + len(f.Note) + len(f.PageURL) }

// handleFeedback stores a note and optional screenshot from the owner or a guest.
func (d *daemon) handleFeedback(w http.ResponseWriter, r *http.Request, c caller, app App) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, codeBadRequest, "POST only")
		return
	}
	// The person's slots are taken before the body is read, so neither repeated nor slow
	// uploads cost more than one read of up to maxFeedbackBody at a time.
	done, ok := limiter.begin(c.userID())
	if !ok {
		writeError(w, http.StatusTooManyRequests, codeRateLimited, "your last feedback is still being sent")
		return
	}
	defer done()
	if !limiter.allow(c.userID(), time.Now()) {
		writeError(w, http.StatusTooManyRequests, codeRateLimited, "that's a lot of feedback for one hour; try again later")
		return
	}
	http.NewResponseController(w).SetReadDeadline(time.Now().Add(feedbackReadTimeout))
	var req feedbackRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxFeedbackBody)).Decode(&req); err != nil {
		code := codeBadRequest
		if errors.As(err, new(*http.MaxBytesError)) {
			code = codeTooLarge
		}
		writeError(w, http.StatusBadRequest, code, "send JSON {note, pageUrl, screenshotPngBase64} under 7 MB")
		return
	}
	note, img, err := validateFeedback(req)
	if err != nil {
		code := codeBadRequest
		if errors.As(err, new(tooLarge)) {
			code = codeTooLarge
		}
		writeError(w, http.StatusBadRequest, code, err.Error())
		return
	}
	fb := Feedback{ID: newID(), At: time.Now(), App: app.Slug, From: c.displayName(), Role: string(c.Role), UserID: c.userID(),
		Device: c.DeviceName, PageURL: stripControl(req.PageURL, false), Note: note}
	if img != nil {
		dir := feedbackDir(d.stateDir)
		fb.Screenshot, fb.ScreenshotBytes = fb.ID+".png", len(img)
		path := filepath.Join(dir, fb.Screenshot)
		if err := jsonfile.MkdirPrivate(dir); err != nil || os.WriteFile(path, img, 0o600) != nil {
			os.Remove(path)
			writeError(w, http.StatusInternalServerError, codeInternal, "couldn't store the screenshot")
			return
		}
	}

	d.sh.mu.Lock()
	dropped := d.sh.st.makeRoomLocked(fb)
	d.sh.st.Feedback = append(d.sh.st.Feedback, fb)
	if err := d.sh.saveLocked(); err != nil {
		log.Printf("[%s] feedback %s: saving: %v", app.Slug, fb.ID, err)
	}
	d.sh.mu.Unlock()
	for _, f := range dropped {
		if path, ok := screenshotPath(d.stateDir, f); ok {
			os.Remove(path)
		}
	}
	if len(dropped) > 0 {
		log.Printf("[%s] feedback inbox full: dropped %d older items", app.Slug, len(dropped))
	}
	log.Printf("[%s] feedback %s from %s (%s)%s", app.Slug, fb.ID, fb.From, fb.Role, map[bool]string{true: " with a screenshot", false: ""}[img != nil])
	writeJSON(w, http.StatusOK, feedbackSentView{ID: fb.ID})
}

// screenshotPath returns where a feedback item's screenshot is stored, refusing names
// that aren't plain file names.
func screenshotPath(stateDir string, fb Feedback) (string, bool) {
	if fb.Screenshot == "" || fb.Screenshot != filepath.Base(fb.Screenshot) || strings.HasPrefix(fb.Screenshot, ".") {
		return "", false
	}
	return filepath.Join(feedbackDir(stateDir), fb.Screenshot), true
}

// readFeedback lists feedback from the state file, newest first, optionally for one
// app. It works without the daemon.
func readFeedback(stateDir, app string, limit int) ([]Feedback, error) {
	st, err := loadSharingState(sharingPath(stateDir))
	if err != nil {
		return nil, err
	}
	var out []Feedback
	for i := len(st.Feedback) - 1; i >= 0; i-- {
		if app == "" || st.Feedback[i].App == app {
			out = append(out, st.Feedback[i])
		}
		if limit > 0 && len(out) == limit {
			break
		}
	}
	return out, nil
}
