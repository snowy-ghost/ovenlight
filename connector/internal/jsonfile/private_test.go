package jsonfile

import (
	"path/filepath"
	"testing"
)

func TestSaveIsPrivate(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "new")
	path := filepath.Join(dir, "state.json")
	for range 2 { // a new file, then one replaced
		if err := Save(path, map[string]string{"k": "v"}); err != nil {
			t.Fatal(err)
		}
		for _, p := range []string{dir, path} {
			if err := CheckPrivate(p); err != nil {
				t.Error(err)
			}
		}
	}
	shareWithEveryone(t, path)
	if err := CheckPrivate(path); err == nil {
		t.Error("a file everyone can read passed")
	}
}
