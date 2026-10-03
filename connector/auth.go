package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"golang.org/x/term"

	"github.com/snowy-ghost/ovenlight/connector/internal/tsapi"
)

// cmdAuth handles `ovenlight auth set|status|remove`. The secret is only ever read from
// standard input, never from the command line, where it would land in shell history
// and the process list.
func cmdAuth(args []string) error {
	if len(args) == 0 {
		return errors.New("usage: ovenlight auth set|status|remove")
	}
	switch sub, rest := args[0], args[1:]; sub {
	case "set":
		return cmdAuthSet(rest)
	case "status":
		return cmdAuthStatus(rest)
	case "remove":
		fs, p := newFlags("auth remove")
		if _, err := parseArgs(fs, rest, 0); err != nil {
			return err
		}
		err := os.Remove(credentialsPath(p.config))
		if errors.Is(err, os.ErrNotExist) {
			fmt.Println("No credential was set.")
			return nil
		}
		if err == nil {
			fmt.Println("Removed the Tailscale API credential. Revoke it in the admin console too if you're done with it.")
		}
		return err
	default:
		return fmt.Errorf("unknown auth command %q; use set, status or remove", sub)
	}
}

func cmdAuthSet(args []string) error {
	fs, p := newFlags("auth set")
	clientID := fs.String("oauth-client-id", "", "use an OAuth client with this ID; its secret is read from stdin")
	apiURL := fs.String("api-url", "", "development only: Tailscale API base URL (default "+tsapi.DefaultBaseURL+")")
	// The argument may be the secret, so it isn't repeated.
	if pos, _ := parseInterspersed(fs, args); len(pos) > 0 {
		return errors.New("the secret is read from standard input, not the command line: pipe it in, or run the command and paste it. A flag's value with spaces goes in quotes")
	}
	if *apiURL != "" && os.Getenv("OVENLIGHT_DEV") != "1" {
		return errors.New("--api-url is for development against a local API server; set OVENLIGHT_DEV=1 to use it")
	}

	kind := "API access token"
	if *clientID != "" {
		kind = "OAuth client secret"
	}
	secret, err := readSecret(os.Stdin, "Paste the "+kind+" (it won't be shown): ")
	if err != nil {
		return err
	}
	creds := tsapi.Credentials{Type: tsapi.TypeToken, Token: secret, BaseURL: *apiURL}
	if *clientID != "" {
		creds = tsapi.Credentials{Type: tsapi.TypeOAuth, ClientID: strings.TrimSpace(*clientID), ClientSecret: secret, BaseURL: *apiURL}
	}
	if err := checkSecretShape(creds); err != nil {
		return err
	}
	fmt.Fprintln(os.Stderr, "Checking the credential with the Tailscale API...")
	if err := verifyCredentials(creds); err != nil {
		return fmt.Errorf("not saved: %w", err)
	}
	path := credentialsPath(p.config)
	if err := tsapi.SaveCredentials(path, creds); err != nil {
		return err
	}
	fmt.Printf("Saved the %s (fingerprint %s) to %s, readable only by you.\n", kind, creds.Fingerprint(), path)
	if creds.Type == tsapi.TypeToken {
		fmt.Println("API access tokens expire (90 days at most); run `ovenlight auth set` again with a new one when it does.")
	}
	fmt.Println("Next: ovenlight publish --slug <app> --shareable")
	return nil
}

// readSecret reads one secret: hidden from a terminal, or all of a pipe.
func readSecret(in *os.File, prompt string) (string, error) {
	var raw []byte
	var err error
	if term.IsTerminal(int(in.Fd())) {
		fmt.Fprint(os.Stderr, prompt)
		raw, err = term.ReadPassword(int(in.Fd()))
		fmt.Fprintln(os.Stderr)
	} else {
		raw, err = io.ReadAll(io.LimitReader(in, 4096))
	}
	if err != nil {
		return "", err
	}
	secret := strings.TrimSpace(string(raw))
	if secret == "" {
		return "", errors.New("no secret on standard input")
	}
	if strings.ContainsAny(secret, " \t\r\n") {
		return "", errors.New("the secret has spaces or line breaks in it; paste just the key")
	}
	return secret, nil
}

// checkSecretShape catches the common mix-ups without printing the secret.
func checkSecretShape(c tsapi.Credentials) error {
	s := c.Token
	if c.Type == tsapi.TypeOAuth {
		s = c.ClientSecret
	}
	switch {
	case strings.HasPrefix(s, "tskey-auth-"):
		return errors.New("that's an auth key (for logging a device in), not an API credential; create an API access token or an OAuth client in the admin console under Settings, Keys or OAuth clients")
	case c.Type == tsapi.TypeToken && strings.HasPrefix(s, "tskey-client-"):
		return errors.New("that's an OAuth client secret; pass its client ID with --oauth-client-id")
	case c.Type == tsapi.TypeOAuth && strings.HasPrefix(s, "tskey-api-"):
		return errors.New("that's an API access token; drop --oauth-client-id")
	}
	return nil
}

// verifyCredentials makes the read-only calls setup-sharing depends on, and says so.
func verifyCredentials(c tsapi.Credentials) error {
	devices, err := credentialAccess(c)
	if err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "ok: it can list your %d devices and read the policy file.\n", devices)
	return nil
}

// credentialAccess makes those calls quietly and returns how many devices it listed.
func credentialAccess(c tsapi.Credentials) (int, error) {
	client, err := tsapi.New(c, nil)
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	devices, err := client.Devices(ctx)
	if err != nil {
		return 0, fmt.Errorf("listing devices failed: %w", err)
	}
	if _, err := client.GetPolicy(ctx); err != nil {
		return 0, fmt.Errorf("reading the policy file failed (an OAuth client needs the policy_file scope): %w", err)
	}
	return len(devices), nil
}

func cmdAuthStatus(args []string) error {
	fs, p := newFlags("auth status")
	check := fs.Bool("check", false, "also check the credential against the API")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	creds, err := tsapi.LoadCredentials(credentialsPath(p.config))
	if err != nil {
		return err
	}
	kind := "API access token"
	if creds.Type == tsapi.TypeOAuth {
		kind = "OAuth client " + creds.ClientID
	}
	base := creds.BaseURL
	if base == "" {
		base = tsapi.DefaultBaseURL
	}
	tailnet := creds.Tailnet
	if tailnet == "" {
		tailnet = "-"
	}
	fmt.Printf("Tailscale API credential: %s, fingerprint %s (tailnet %s, %s)\n", kind, creds.Fingerprint(), tailnet, base)
	if *check {
		return verifyCredentials(creds)
	}
	return nil
}
