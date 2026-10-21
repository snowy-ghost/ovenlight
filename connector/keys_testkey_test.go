//go:build ovenlight_testkey

package main

import (
	"os"
	"strings"
	"testing"
)

func TestTestKeyMatchesTestdata(t *testing.T) {
	pub, err := os.ReadFile("testdata/releasekey/test-key.pub")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(string(pub)) != testKey {
		t.Error("testKey isn't testdata/releasekey/test-key.pub")
	}
}
