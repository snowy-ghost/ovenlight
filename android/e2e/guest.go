//go:build ignore

// A guest's phone for e2e.sh's sharing scenario: joins a local Headscale with an invite's
// key and claims the invite, as Ovenlight does, so the owner has someone to remove. The
// invite link comes on stdin, never the command line. e2e.sh runs it with the connector's
// module, which has tsnet:
//
//	go -C connector run ../android/e2e/guest.go <control URL> <state folder> <test CA> < link
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"tailscale.com/tsnet"
)

func main() {
	if err := run(os.Args[1], os.Args[2], os.Args[3]); err != nil {
		fmt.Fprintln(os.Stderr, "guest:", err)
		os.Exit(1)
	}
}

func run(control, dir, caFile string) error {
	link, err := io.ReadAll(os.Stdin)
	if err != nil {
		return err
	}
	u, err := url.Parse(strings.TrimSpace(string(link)))
	if err != nil {
		return fmt.Errorf("not a link")
	}
	fields, err := url.ParseQuery(u.EscapedFragment())
	key, host := fields.Get("key"), fields.Get("host")
	if err != nil || key == "" || host == "" {
		return fmt.Errorf("not an invite link")
	}
	ca, err := os.ReadFile(caFile)
	if err != nil {
		return err
	}
	roots := x509.NewCertPool()
	roots.AppendCertsFromPEM(ca)

	srv := &tsnet.Server{Dir: dir, Hostname: "ovenlight-guest", AuthKey: key, ControlURL: control, Logf: func(string, ...any) {}}
	defer srv.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	if _, err := srv.Up(ctx); err != nil {
		return fmt.Errorf("joining: %w", err)
	}
	client := srv.HTTPClient()
	transport := client.Transport.(*http.Transport).Clone()
	transport.TLSClientConfig = &tls.Config{RootCAs: roots}
	client.Transport, client.Timeout = transport, 10*time.Second

	// The connector may not know the new device for a few seconds (unknown_caller), as the
	// app's claim allows for.
	body, _ := json.Marshal(map[string]string{"key": key})
	last := ""
	for deadline := time.Now().Add(30 * time.Second); time.Now().Before(deadline); time.Sleep(time.Second) {
		resp, err := client.Post("https://"+host+"/__ovenlight/claim", "application/json", bytes.NewReader(body))
		if err != nil {
			last = err.Error()
			continue
		}
		answer, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode == http.StatusOK {
			fmt.Println("claimed")
			return nil
		}
		last = fmt.Sprintf("%d %s", resp.StatusCode, answer)
	}
	return fmt.Errorf("the claim never went through: %s", last)
}
