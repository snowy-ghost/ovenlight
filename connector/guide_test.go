package main

import (
	"encoding/json"
	"os"
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

func TestGuideMatchesDocs(t *testing.T) {
	want, err := os.ReadFile("../docs/building-apps.md")
	if err != nil {
		t.Fatal(err)
	}
	if buildingGuide != string(want) {
		t.Fatal("connector/building-apps.md differs from docs/building-apps.md: run `go generate` in connector/")
	}
}
