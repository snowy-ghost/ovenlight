package main

import (
	"bytes"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"hash/fnv"
	"html"
	"image"
	"image/color"
	"image/png"
	"io/fs"
	"math"
	"net"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// starterFiles is the app `ovenlight new` writes, with placeholders such as {{name}} that
// writeStarter fills in.
//
//go:embed all:starter
var starterFiles embed.FS

// starterApp is what a new starter app is made from.
type starterApp struct {
	Name string
	Slug string
	Port int
}

// starterAccents are the accent colors a starter app can get, for light and dark, picked
// by its slug so that two starters don't look alike. Each passes WCAG AA for white text on
// the light one and black text on the dark one.
var starterAccents = [][2]string{
	{"#0b7a75", "#5fd1c6"}, // teal
	{"#1f63c6", "#7ab4ff"}, // blue
	{"#4f46c8", "#a9a4ff"}, // indigo
	{"#8034a8", "#d29cf5"}, // purple
	{"#b8306a", "#ff8fbd"}, // pink
	{"#b5471a", "#ff9f6e"}, // orange
	{"#2e7d32", "#7fd88a"}, // green
	{"#3f5b75", "#9fc1df"}, // slate
}

func cmdNew(args []string) error {
	flags, p := newFlags("new")
	parent := flags.String("dir", ".", "folder to create the app's folder in")
	port := flags.Int("port", 0, "port the app listens on (default: a free one from 20000 to 29999, chosen by the name)")
	pos, at := parseInterspersed(flags, args)
	if len(pos) == 0 {
		return errors.New(`usage: ovenlight new "<App Name>" [--dir <parent>] [--port <n>]`)
	}
	// The name's words may go unquoted, but a word after a flag's value, as in
	// --dir My Projects, may belong to that value. It is the name when it is the only one
	// and is clearly whole: it holds a space, so it was quoted, or the value is a folder
	// that exists.
	if i := at[0]; afterValue(flags, args, i) && !(len(pos) == 1 && (strings.Contains(pos[0], " ") || isDir(expandHome(args[i-1])))) {
		// Values as typed, in plain quotes: %q would double a Windows path's backslashes.
		return fmt.Errorf(`unexpected argument %q after %s "%s": give the name first: ovenlight new "<App Name>" --dir <parent>, and put a value with spaces in quotes, such as %s "%s"`,
			pos[0], args[i-2], args[i-1], args[i-2], args[i-1]+" "+pos[0])
	}
	for i := 1; i < len(at); i++ {
		if at[i] != at[i-1]+1 {
			return strayArgument(flags, args, at[i])
		}
	}
	app := starterApp{Name: strings.TrimSpace(strings.Join(pos, " "))}
	if strings.ContainsFunc(app.Name, unicode.IsControl) {
		return errors.New("the app name can't contain control characters")
	}
	app.Slug = Slugify(app.Name)
	if app.Slug == "" {
		return fmt.Errorf("%q has no letters a to z or digits to make its hostname from; name it with some", app.Name)
	}
	taken := map[int]bool{}
	if cfg, err := LoadConfig(p.config); err == nil {
		for _, a := range cfg.Apps {
			taken[a.Port] = true
		}
	}
	chosen := ""
	switch {
	case *port != 0:
		app.Port = *port
		if taken[app.Port] || !portFree(app.Port) {
			fmt.Printf("Note: port %d is in use already, by a published app or something on this computer.\n", app.Port)
		}
	default:
		app.Port = starterPort(app.Slug, taken)
		chosen = " (chosen by its name; --port picks another)"
	}
	if err := (App{Name: app.Name, Slug: app.Slug, Port: app.Port}).Validate(); err != nil {
		return err
	}
	dir, err := filepath.Abs(filepath.Join(expandHome(*parent), app.Slug))
	if err != nil {
		return err
	}
	if err := writeStarter(dir, app); err != nil {
		return err
	}

	fmt.Printf("Created %s in %s, on port %d%s.\n\n", app.Name, dir, app.Port, chosen)
	npm := "npm"
	if runtime.GOOS == "windows" {
		npm = "npm.cmd" // Windows PowerShell's npm is npm.ps1, which its default policy refuses to run
	}
	fmt.Printf("Try it on this computer (a person in a terminal; a coding agent publishes it below instead):\n  cd %s\n  %s start\nthen open http://127.0.0.1:%d/ (Ctrl-C stops it).\n\n", shellQuote(dir), npm, app.Port)
	fmt.Printf("Put it on your phone (a coding agent's path); first stop any copy you started (Ctrl-C, or kill with the process ID you started), since the connector runs its own on the same port and keeps it running:\n  %s --dir %s\n  %s check%s %s\n\n",
		publishCommand(ovenlightCommand(), app, pathFlags(p)), shellQuote(dir), ovenlightCommand(), pathFlags(p), app.Slug)
	starter := App{Name: app.Name, Slug: app.Slug, Run: "npm start", Dir: dir}
	for _, c := range []*Check{protectedFolderCheck(starter), temporaryFolderCheck(starter)} {
		if c != nil {
			printChecks([]Check{*c}, false)
			fmt.Println()
		}
	}
	if _, err := exec.LookPath("node"); err != nil {
		fmt.Println("Node isn't on your PATH: the app needs Node 22.13 or later, from https://nodejs.org or your package manager.")
	}
	fmt.Println("The guide to building apps for Ovenlight: ovenlight guide")
	return nil
}

func isDir(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

// publishCommand is the command that publishes a starter app and has the connector run
// it, with ovenlight as the binary, such as ovenlightCommand. flags are publish's first,
// such as pathFlags.
func publishCommand(ovenlight string, app starterApp, flags string) string {
	return fmt.Sprintf(`%s publish%s --port %d --name %s --run 'npm start'`, ovenlight, flags, app.Port, shellQuote(app.Name))
}

// shellQuote quotes s for a POSIX shell: in double quotes, unless s holds a character
// they don't protect.
func shellQuote(s string) string {
	if strings.ContainsAny(s, "\"\\$`!") {
		return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
	}
	return `"` + s + `"`
}

// starterPort picks the port for a new app: one from 20000 to 29999, starting from where
// the slug hashes to, so a name always gets the same one, and moving past any port a
// published app has or something on this computer answers on. The range is clear of the
// usual development ports (3000, 5173, 8000, 8080) and below the ports systems hand out
// for outgoing connections (32768 and up on Linux, 49152 and up on macOS and Windows).
func starterPort(slug string, taken map[int]bool) int {
	const first, count = 20000, 10000
	h := fnv.New32a()
	h.Write([]byte(slug))
	start := int(h.Sum32() % count)
	for i := range count {
		port := first + (start+i)%count
		if !taken[port] && portFree(port) {
			return port
		}
	}
	return first + start
}

// portFree reports whether nothing answers on 127.0.0.1:port, where the connector would
// reach the app.
func portFree(port int) bool {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 500*time.Millisecond)
	if err != nil {
		return true
	}
	conn.Close()
	return false
}

// writeStarter writes the starter app into dir, which must be missing or empty.
func writeStarter(dir string, app starterApp) error {
	entries, err := os.ReadDir(dir)
	switch {
	case err == nil && len(entries) > 0:
		return fmt.Errorf("%s already exists and isn't empty: choose another name, or another folder with --dir", dir)
	case err != nil && !errors.Is(err, fs.ErrNotExist):
		return err
	}
	h := fnv.New32a()
	h.Write([]byte(app.Slug))
	accent := starterAccents[h.Sum32()%uint32(len(starterAccents))]
	jsonName, _ := json.Marshal(app.Name)
	values := []string{"{{slug}}", app.Slug, "{{port}}", strconv.Itoa(app.Port),
		"{{accent}}", accent[0], "{{accentDark}}", accent[1], "{{publish}}", publishCommand("ovenlight", app, "")}
	// The name goes into each kind of file escaped for it.
	names := map[string]string{".html": html.EscapeString(app.Name), ".md": app.Name,
		".json": string(jsonName[1 : len(jsonName)-1]), ".webmanifest": string(jsonName[1 : len(jsonName)-1])}

	err = fs.WalkDir(starterFiles, "starter", func(name string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		target := filepath.Join(dir, filepath.FromSlash(strings.TrimPrefix(name, "starter")))
		if d.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		data, err := starterFiles.ReadFile(name)
		if err != nil {
			return err
		}
		pairs := values
		if n, ok := names[path.Ext(name)]; ok {
			pairs = append([]string{"{{name}}", n}, values...)
		}
		return os.WriteFile(target, []byte(strings.NewReplacer(pairs...).Replace(string(data))), 0o644)
	})
	if err != nil {
		return err
	}
	for file, size := range map[string]int{"icon-512.png": 512, "apple-touch-icon.png": 180} {
		var b bytes.Buffer
		if err := png.Encode(&b, starterIcon(size, accent[0])); err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(dir, "public", file), b.Bytes(), 0o644); err != nil {
			return err
		}
	}
	return nil
}

// starterIcon draws a placeholder icon: a checklist in white on the accent color, full
// bleed, since iOS rounds the corners itself.
func starterIcon(size int, accent string) image.Image {
	var bg color.RGBA
	fmt.Sscanf(accent, "#%02x%02x%02x", &bg.R, &bg.G, &bg.B)
	bg.A = 255
	// In units of the icon's side: three rows of a dot (filled on the first) and a bar.
	rows := []float64{0.33, 0.5, 0.67}
	inGlyph := func(x, y float64) bool {
		for i, ry := range rows {
			d := math.Hypot(x-0.315, y-ry)
			if d <= 0.056 && (i == 0 || d >= 0.034) {
				return true
			}
			bx := math.Max(0.43, math.Min(0.73, x))
			if math.Hypot(x-bx, y-ry) <= 0.03 {
				return true
			}
		}
		return false
	}
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	const samples = 4 // per side, for smooth edges
	for py := range size {
		for px := range size {
			hits := 0
			for sy := range samples {
				for sx := range samples {
					x := (float64(px) + (float64(sx)+0.5)/samples) / float64(size)
					y := (float64(py) + (float64(sy)+0.5)/samples) / float64(size)
					if inGlyph(x, y) {
						hits++
					}
				}
			}
			a := float64(hits) / samples / samples
			mix := func(c uint8) uint8 { return uint8(math.Round(float64(c) + (255-float64(c))*a)) }
			img.SetRGBA(px, py, color.RGBA{mix(bg.R), mix(bg.G), mix(bg.B), 255})
		}
	}
	return img
}
