package stbridge

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/syncthing"
)

// Starts the real core in a temp home, checks REST auth, and restarts it in
// the same process (the service relies on that after a stop).
func TestStartStopRestart(t *testing.T) {
	home := t.TempDir()
	for round := range 2 {
		if err := Start(home, "test-key", "android", "secret"); err != nil {
			t.Fatalf("round %d: start: %v", round, err)
		}
		if !IsRunning() || GuiAddress() == "" || DeviceID() == "" {
			t.Fatalf("round %d: not running after start", round)
		}

		req, _ := http.NewRequest(http.MethodGet, "http://"+GuiAddress()+"/rest/system/status", nil)
		req.Header.Set("X-API-Key", "test-key")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("round %d: status %d with API key", round, resp.StatusCode)
		}

		unauth, err := http.Get("http://" + GuiAddress() + "/rest/system/status")
		if err != nil {
			t.Fatal(err)
		}
		unauth.Body.Close()
		if unauth.StatusCode == http.StatusOK {
			t.Fatalf("round %d: REST reachable without credentials", round)
		}

		Stop()
		if IsRunning() {
			t.Fatalf("round %d: still running after stop", round)
		}
	}
}

// The web GUI's settings save goes through the same config API: the login and
// access settings must be refused, anything else must still go through.
func TestGUILoginLocked(t *testing.T) {
	if err := Start(t.TempDir(), "test-key", "android", "secret"); err != nil {
		t.Fatal(err)
	}
	defer Stop()

	patch := func(body string) int {
		req, _ := http.NewRequest(http.MethodPatch, "http://"+GuiAddress()+"/rest/config/gui", strings.NewReader(body))
		req.Header.Set("X-API-Key", "test-key")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	for _, body := range []string{
		`{"password": ""}`,
		`{"user": "someone"}`,
		`{"insecureAdminAccess": true}`,
		`{"metricsWithoutAuth": true}`,
		`{"apiKey": "another-key"}`,
	} {
		if code := patch(body); code == http.StatusOK {
			t.Errorf("%s: accepted", body)
		}
	}
	// The app's own way in: Basic auth on the page load (REST calls would also need the CSRF token).
	if code, in := basicAuth(t, "android", "secret"); code != http.StatusOK || !in {
		t.Fatalf("GUI login no longer works: HTTP %d, session %v", code, in)
	}
	if _, in := basicAuth(t, "android", "wrong"); in {
		t.Fatal("a wrong password starts a session")
	}

	// Last: a GUI change restarts the GUI listener.
	if code := patch(`{"theme": "dark"}`); code != http.StatusOK {
		t.Errorf("theme change refused: %d", code)
	}
}

// "Restart" in the web GUI restarts the core in place: same address, and
// IsRunning never reports a gap the app would read as a crash.
func TestRestartInPlace(t *testing.T) {
	if err := Start(t.TempDir(), "test-key", "android", "secret"); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	addr := GuiAddress()
	current := func() *syncthing.App {
		mu.Lock()
		defer mu.Unlock()
		return app
	}
	before := current()

	call := func(method, path string) error {
		req, _ := http.NewRequest(method, "http://"+addr+path, nil)
		req.Header.Set("X-API-Key", "test-key")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return err
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("HTTP %d", resp.StatusCode)
		}
		return nil
	}
	if err := call(http.MethodPost, "/rest/system/restart"); err != nil {
		t.Fatal(err)
	}

	for deadline := time.Now().Add(30 * time.Second); ; time.Sleep(50 * time.Millisecond) {
		if !IsRunning() {
			t.Fatalf("not running during restart: %q", LastError())
		}
		if a := current(); a != before && call(http.MethodGet, "/rest/system/status") == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("core did not come back after restart")
		}
	}
	if GuiAddress() != addr {
		t.Fatalf("address changed: %s -> %s", addr, GuiAddress())
	}
}

// Credentials that can't work as an HTTP header, a Basic auth login or a
// bcrypt password are refused before anything is written.
func TestStartRefusesBadCredentials(t *testing.T) {
	for _, c := range [][3]string{
		{"", "android", "secret"},
		{"key", "", "secret"},
		{"key", "android", ""},
		{"key", "and:roid", "secret"},
		{"key\n", "android", "secret"},
		{"key", "android", strings.Repeat("x", 73)},
		{"key", "android", "$2a$10$" + strings.Repeat("a", 53)},
	} {
		home := t.TempDir()
		if err := Start(home, c[0], c[1], c[2]); err == nil {
			Stop()
			t.Errorf("%q: started", c)
		}
		if entries, _ := os.ReadDir(home); len(entries) != 0 {
			t.Errorf("%q: wrote %d files", c, len(entries))
		}
	}
}

// A damaged identity stops the start instead of being replaced by a new one.
func TestDamagedIdentityKept(t *testing.T) {
	home := t.TempDir()
	if err := Start(home, "test-key", "android", "secret"); err != nil {
		t.Fatal(err)
	}
	id := DeviceID()
	Stop()
	key := filepath.Join(home, "key.pem")
	b, _ := os.ReadFile(key)
	if err := os.WriteFile(key, b[:len(b)/2], 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Start(home, "test-key", "android", "secret"); err == nil {
		Stop()
		t.Fatal("started with a damaged key")
	}
	if after, _ := os.ReadFile(key); len(after) != len(b)/2 {
		t.Fatal("damaged key replaced")
	}
	if err := os.WriteFile(key, b, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Start(home, "test-key", "android", "secret"); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	if DeviceID() != id {
		t.Fatalf("device ID changed: %s -> %s", id, DeviceID())
	}
}

func TestCheckIgnores(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "other.txt"), []byte("*.bak\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	for patterns, ok := range map[string]bool{
		"":                        true,
		"*.tmp\n!keep.tmp\n// x":  true,
		"(?i)thumbs.db\n(?d)*.db": true,
		"#include other.txt":      true,
		"!":                       false, // the "!" chip on an empty line
		"(?i)":                    false,
		"a[b":                     false,
		"#include missing.txt":    false,
	} {
		if got := CheckIgnores(dir, patterns) == ""; got != ok {
			t.Errorf("%q: ok %v, want %v (%s)", patterns, got, ok, CheckIgnores(dir, patterns))
		}
	}
	if CheckIgnores("", "#include other.txt") == "" {
		t.Error("defaults: an #include can't exist in a new folder")
	}
}

// The REST API addresses a folder by its ID in the path. The app's Folder ID
// check allows exactly the IDs that work there.
func TestFolderIDsInRESTPaths(t *testing.T) {
	if err := Start(t.TempDir(), "test-key", "android", "secret"); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	call := func(method, path, body string) int {
		req, _ := http.NewRequest(method, "http://"+GuiAddress()+path, strings.NewReader(body))
		req.Header.Set("X-API-Key", "test-key")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	for id, addressable := range map[string]bool{
		"abcde-12345": true, "My photos": true, "a%b": true, "a?b#c": true, `a\b`: true, "ä€😀": true, "...": true,
		"a/b": false, ".": false, "..": false,
	} {
		body, _ := json.Marshal(map[string]string{"id": id, "path": t.TempDir()})
		if code := call(http.MethodPost, "/rest/config/folders", string(body)); code != http.StatusOK {
			t.Fatalf("%q: add: %d", id, code)
		}
		// Escaped as OkHttp's addPathSegment does; "." and ".." it can't send at all.
		got := id != "." && id != ".." && call(http.MethodPatch, "/rest/config/folders/"+url.PathEscape(id), `{"label": "x"}`) == http.StatusOK
		if got != addressable {
			t.Errorf("%q: addressable %v, want %v", id, got, addressable)
		}
	}
}

// A new config gives new devices one connection; a config that exists keeps
// what it has, so a later start (or an imported config) is never rewritten.
func TestNewDevicesUseOneConnection(t *testing.T) {
	home := t.TempDir()
	type dev struct {
		NumConnections int `json:"numConnections"`
	}
	send := func(method, path, body string) {
		t.Helper()
		req, _ := http.NewRequest(method, "http://"+GuiAddress()+path, strings.NewReader(body))
		req.Header.Set("X-API-Key", "app-key")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("%s %s: HTTP %d", method, path, resp.StatusCode)
		}
	}

	if err := Start(home, "app-key", "android", "secret"); err != nil {
		t.Fatal(err)
	}
	var d dev
	getJSON(t, "/rest/config/defaults/device", &d)
	if d.NumConnections != phoneConnections {
		t.Fatalf("fresh config: default device has %d connections, want %d", d.NumConnections, phoneConnections)
	}
	// A device added without a value takes the default.
	peer := protocol.NewDeviceID([]byte("a new peer")).String()
	send(http.MethodPost, "/rest/config/devices", `{"deviceID":"`+peer+`"}`)
	getJSON(t, "/rest/config/devices/"+peer, &d)
	if d.NumConnections != phoneConnections {
		t.Fatalf("new device has %d connections, want %d", d.NumConnections, phoneConnections)
	}
	// The user's own choice survives the next start.
	send(http.MethodPatch, "/rest/config/defaults/device", `{"numConnections":0}`)
	Stop()

	if err := Start(home, "app-key", "android", "secret"); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	getJSON(t, "/rest/config/defaults/device", &d)
	if d.NumConnections != 0 {
		t.Fatalf("existing config rewritten: default device has %d connections, want 0", d.NumConnections)
	}
}

// Every Start brings a new login, and the old one stops working: its API key,
// its Basic auth and the web GUI sessions it opened. A restart in place keeps
// the run's login and sessions, so the open web GUI stays logged in.
func TestLoginNewEveryStart(t *testing.T) {
	home := t.TempDir()
	t.Cleanup(Stop)
	status := func(set func(*http.Request)) int {
		t.Helper()
		req, _ := http.NewRequest(http.MethodGet, "http://"+GuiAddress()+"/rest/system/status", nil)
		set(req)
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	key := func(k string) func(*http.Request) {
		return func(r *http.Request) { r.Header.Set("X-API-Key", k) }
	}
	// login logs in as the WebView does, with Basic auth on the page load, and
	// returns how the GUI's scripts then call REST: with the session and CSRF
	// cookies and the CSRF header; nil when no session was opened.
	login := func(user, password string) func(*http.Request) {
		t.Helper()
		req, _ := http.NewRequest(http.MethodGet, "http://"+GuiAddress()+"/", nil)
		req.SetBasicAuth(user, password)
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		cookies := resp.Cookies()
		if !slices.ContainsFunc(cookies, func(c *http.Cookie) bool { return strings.HasPrefix(c.Name, "sessionid-") }) {
			return nil
		}
		return func(r *http.Request) {
			for _, c := range cookies {
				r.AddCookie(c)
				if id, ok := strings.CutPrefix(c.Name, "CSRF-Token-"); ok {
					r.Header.Set("X-CSRF-Token-"+id, c.Value)
				}
			}
		}
	}

	if err := Start(home, "key-1", "user-1", "pass-1"); err != nil {
		t.Fatal(err)
	}
	session := login("user-1", "pass-1")
	if session == nil {
		t.Fatal("run 1: the login opens no session")
	}
	if code := status(session); code != http.StatusOK {
		t.Fatalf("run 1: session: HTTP %d", code)
	}
	if code := status(key("key-1")); code != http.StatusOK {
		t.Fatalf("run 1: API key: HTTP %d", code)
	}

	// Restart in place: same login, the session still works. lib/api saves
	// sessions after a second without logins (tokenmanager.go).
	time.Sleep(1500 * time.Millisecond)
	mu.Lock()
	before := app
	mu.Unlock()
	req, _ := http.NewRequest(http.MethodPost, "http://"+GuiAddress()+"/rest/system/restart", nil)
	key("key-1")(req)
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	for deadline := time.Now().Add(30 * time.Second); ; time.Sleep(50 * time.Millisecond) {
		mu.Lock()
		back := app != nil && app != before
		mu.Unlock()
		if back && status(key("key-1")) == http.StatusOK {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("core did not come back after restart: %q", LastError())
		}
	}
	if code := status(session); code != http.StatusOK {
		t.Fatalf("restart in place ended the session: HTTP %d", code)
	}
	Stop()

	if err := Start(home, "key-2", "user-2", "pass-2"); err != nil {
		t.Fatal(err)
	}
	if code := status(key("key-1")); code == http.StatusOK {
		t.Fatal("run 2: the old API key still works")
	}
	if code := status(session); code == http.StatusOK {
		t.Fatal("run 2: the old session still works")
	}
	if login("user-1", "pass-1") != nil {
		t.Fatal("run 2: the old login still opens a session")
	}
	if code := status(key("key-2")); code != http.StatusOK {
		t.Fatalf("run 2: new API key: HTTP %d", code)
	}
	if s := login("user-2", "pass-2"); s == nil || status(s) != http.StatusOK {
		t.Fatal("run 2: the new login opens no working session")
	}
}
