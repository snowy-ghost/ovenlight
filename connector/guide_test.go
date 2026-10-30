package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The guide tool answers with the guide as written, not as a JSON string.
func TestMCPGuide(t *testing.T) {
	in := `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"guide","arguments":{}}}`
	var out strings.Builder
	if err := serveMCP(strings.NewReader(in), &out, &paths{}); err != nil {
		t.Fatal(err)
	}
	var reply struct {
		Result struct {
			Content []struct{ Text string } `json:"content"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(out.String()), &reply); err != nil {
		t.Fatal(err)
	}
	if len(reply.Result.Content) != 1 || reply.Result.Content[0].Text != buildingGuide {
		t.Errorf("guide tool answered %s", out.String())
	}
}

// A tool's answer reaches the agent with <, > and & as written, so its fences read as the
// tags the tool descriptions name.
func TestMCPResultsKeepMarkup(t *testing.T) {
	dir := t.TempDir()
	p := &paths{config: filepath.Join(dir, "config.json"), state: dir}
	os.MkdirAll(filepath.Dir(appLogPath(p.state, "coach")), 0o700)
	if err := os.WriteFile(appLogPath(p.state, "coach"), []byte("GET /?a=1&b=2 <b>\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	in := `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"logs","arguments":{"slug":"coach"}}}`
	var out strings.Builder
	if err := serveMCP(strings.NewReader(in), &out, p); err != nil {
		t.Fatal(err)
	}
	var reply struct {
		Result struct {
			Content []struct{ Text string } `json:"content"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(out.String()), &reply); err != nil || len(reply.Result.Content) != 1 {
		t.Fatalf("%v: %s", err, out.String())
	}
	if text := reply.Result.Content[0].Text; !strings.Contains(text, `"output": "<untrusted-`) || !strings.Contains(text, `GET /?a=1&b=2 <b>\n</untrusted-`) {
		t.Errorf("logs answered %s", text)
	}
}

func TestGuideMatchesDocs(t *testing.T) {
	want, err := os.ReadFile("../docs/building-apps.md")
	if err != nil {
		t.Fatal(err)
	}
	if buildingGuide != string(want) {
		t.Fatal("connector/building-apps.md differs from docs/building-apps.md: run `go generate` in connector/")
	}
}
