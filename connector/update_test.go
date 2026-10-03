package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestUpdateCheck(t *testing.T) {
	answer := `{"latest": "1.2.0", "secure": "1.1.3"}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(answer)) }))
	defer srv.Close()
	defer func(u, v string) { latestURL, releaseVersion = u, v }(latestURL, releaseVersion)
	latestURL = srv.URL
	for v, want := range map[string]string{"1.1.0": statusFail, "1.1.3": statusWarn, "1.2.0": statusOK, "1.10.0": statusOK} {
		releaseVersion = v
		if c := updateCheck(); c == nil || c.Status != want {
			t.Errorf("%s: %+v, want %s", v, c, want)
		}
	}
	releaseVersion = "" // a development build
	if c := updateCheck(); c != nil {
		t.Errorf("devel: %+v", c)
	}
	releaseVersion, answer = "1.0.0", "not json"
	if c := updateCheck(); c != nil {
		t.Errorf("unreadable answer: %+v", c)
	}
}
