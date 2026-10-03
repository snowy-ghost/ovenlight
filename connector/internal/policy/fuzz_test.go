package policy

import (
	"os"
	"path/filepath"
	"testing"
)

// Whatever policy the tailnet holds, planning the change doesn't fail badly, and a plan
// applied is one planning leaves alone.
func FuzzPlan(f *testing.F) {
	seeds, _ := filepath.Glob("testdata/*.hujson")
	for _, s := range seeds {
		if data, err := os.ReadFile(s); err == nil {
			f.Add(data)
		}
	}
	f.Add([]byte(`{"acls": [{"action": "accept", "src": ["*"], "dst": ["*:*"]}]}`))
	cfg := Config{Owner: "alex@example.com", Apps: []string{"coach"}}
	f.Fuzz(func(t *testing.T, current []byte) {
		planned, _, _, err := Plan(current, nil, cfg)
		if err != nil {
			return
		}
		again, _, _, err := Plan(planned, nil, cfg)
		if err != nil {
			t.Fatalf("planning its own plan failed: %v\n%s", err, planned)
		}
		if same, err := Equivalent(planned, again); err != nil || !same {
			t.Errorf("a plan applied changed again:\n%s\n---\n%s", planned, again)
		}
	})
}
