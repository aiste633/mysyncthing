package stbridge

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/thejerf/suture/v4"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/events"
	"github.com/syncthing/syncthing/lib/fs"
	"github.com/syncthing/syncthing/lib/ignore"
	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/svcutil"
)

// Fuzz targets for what reaches the bridge from outside the app's control: a
// config.xml, cert or key written by something else (another Syncthing app,
// an import, a damaged file), and config changes sent to the REST API, as the
// web GUI does. Each asserts the invariant that matters, not just "no panic":
// whenever the core would run, only the app's login opens the GUI.
//
// Run one with, for example: go test -run '^$' -fuzz '^FuzzPrepare$' -fuzztime 1h

const (
	fuzzUser     = "android"
	fuzzPassword = "secret-for-the-fuzz-tests"
	fuzzAPIKey   = "fuzz-api-key"
	fuzzAddr     = "127.0.0.1:1" // never listened on: prepare does not start the GUI
)

// requireLocked fails unless gui lets in exactly the app: its user and
// password, static auth, no TLS, no insecure switches.
func requireLocked(t *testing.T, gui config.GUIConfiguration, user, password string) {
	t.Helper()
	if !gui.Enabled || gui.RawUseTLS || gui.AuthMode != config.AuthModeStatic ||
		gui.InsecureAdminAccess || gui.InsecureSkipHostCheck || gui.MetricsWithoutAuth {
		t.Fatalf("GUI not locked: %+v", lockOf(gui))
	}
	if gui.User != user || gui.CompareHashedPassword(password) != nil {
		t.Fatalf("GUI login is not the app's (user %q)", gui.User)
	}
	if !gui.IsAuthEnabled() {
		t.Fatal("GUI auth disabled")
	}
	if gui.Address() != fuzzAddr || !gui.IsValidAPIKey(fuzzAPIKey) {
		t.Fatalf("env overrides lost: address %q", gui.Address())
	}
}

// A config.xml, cert.pem and key.pem from elsewhere, plus the login the app
// passes. prepare may refuse them, but what it accepts must be locked to that
// login, and an identity that is there must never be replaced silently.
func FuzzPrepare(f *testing.F) {
	seed := t0Home(f)
	cfgXML, _ := os.ReadFile(filepath.Join(seed, "config.xml"))
	cert, _ := os.ReadFile(filepath.Join(seed, "cert.pem"))
	key, _ := os.ReadFile(filepath.Join(seed, "key.pem"))
	f.Add(cfgXML, cert, key, fuzzUser, fuzzPassword)
	f.Add(cfgXML, cert, key, fuzzUser, strings.Repeat("x", 73)) // past bcrypt's 72 bytes
	f.Add(cfgXML, cert, key, fuzzUser, "$2a$10$"+strings.Repeat("a", 53))
	f.Add(cfgXML, cert, key, "", "")
	f.Add([]byte(`<configuration version="37"><gui enabled="false" tls="true"><user>x</user><password></password><insecureAdminAccess>true</insecureAdminAccess></gui></configuration>`), cert, key, fuzzUser, fuzzPassword)
	f.Add([]byte(`<configuration version="99"></configuration>`), cert, key, fuzzUser, fuzzPassword)
	f.Add([]byte{}, []byte{}, []byte{}, fuzzUser, fuzzPassword)
	f.Add(cfgXML, cert[:len(cert)/2], key, fuzzUser, fuzzPassword)
	f.Add(cfgXML, cert, key[:len(key)/2], fuzzUser, fuzzPassword)

	f.Fuzz(func(t *testing.T, cfgXML, cert, key []byte, user, password string) {
		home := t.TempDir()
		write := func(name string, b []byte) {
			if len(b) > 0 {
				if err := os.WriteFile(filepath.Join(home, name), b, 0o600); err != nil {
					t.Fatal(err)
				}
			}
		}
		write("config.xml", cfgXML)
		write("cert.pem", cert)
		write("key.pem", key)
		hadKey := len(key) > 0

		ctx, cancel := context.WithCancel(context.Background())
		early := suture.New("fuzz", svcutil.SpecWithDebugLogger())
		done := early.ServeBackground(ctx)
		defer func() { cancel(); <-done }()

		c, cfg, _, err := prepare(early, params{home, fuzzAPIKey, user, password, fuzzAddr})
		if hadKey {
			if after, _ := os.ReadFile(filepath.Join(home, "key.pem")); string(after) != string(key) {
				t.Fatalf("key.pem replaced (prepare error: %v)", err)
			}
		}
		if err != nil {
			return
		}
		requireLocked(t, cfg.GUI(), user, password)
		if cfg.Options().StartBrowser {
			t.Fatal("StartBrowser on")
		}
		if id := protocol.NewDeviceID(c.Certificate[0]); cfg.MyID() != id {
			t.Fatalf("config ID %v, certificate ID %v", cfg.MyID(), id)
		}
		// What is on disk starts the next run the same way.
		saved, _, err := config.Load(filepath.Join(home, "config.xml"), cfg.MyID(), events.NoopLogger)
		if err != nil {
			t.Fatalf("saved config unreadable: %v", err)
		}
		requireLocked(t, saved.RawCopy().GUI, user, password)
	})
}

// A config change as the REST API applies it: PATCH /rest/config/gui decodes
// the body onto the current GUI settings. guiGuard either refuses it or the
// GUI stays locked to the app's login.
func FuzzGUIGuard(f *testing.F) {
	for _, s := range []string{
		`{"theme": "dark"}`, `{"password": ""}`, `{"user": "someone"}`, `{"enabled": false}`,
		`{"useTLS": true}`, `{"authMode": "ldap"}`, `{"insecureAdminAccess": true}`,
		`{"insecureSkipHostcheck": true}`, `{"metricsWithoutAuth": true}`, `{"address": "0.0.0.0:8384"}`,
		`{"apiKey": "other"}`, `{"user": "android", "password": "secret"}`, `{"debugging": true}`,
	} {
		f.Add([]byte(s))
	}
	f.Setenv("STGUIADDRESS", fuzzAddr)
	f.Setenv("STGUIAPIKEY", fuzzAPIKey)
	f.Fuzz(func(t *testing.T, body []byte) {
		from := config.New(protocol.EmptyDeviceID)
		from.GUI = locked(from.GUI)
		from.GUI.User = fuzzUser
		from.GUI.Password = lockedHash // bcrypt is too slow to hash per input
		to := from.Copy()
		if json.Unmarshal(body, &to.GUI) != nil {
			return
		}
		if (guiGuard{}).VerifyConfiguration(from, to) != nil {
			return
		}
		requireLocked(t, to.GUI, fuzzUser, fuzzPassword)
	})
}

// bcrypt hash of fuzzPassword, made once.
var lockedHash = func() string {
	var g config.GUIConfiguration
	if err := g.SetPassword(fuzzPassword); err != nil {
		panic(err)
	}
	return g.Password
}()

// The same through the running core, for whole-config writes too (PUT
// /rest/config, the web GUI's Save): afterwards the app's login still works
// and nothing else gets in.
func FuzzRESTConfig(f *testing.F) {
	if err := Start(f.TempDir(), fuzzAPIKey, fuzzUser, fuzzPassword); err != nil {
		f.Fatal(err)
	}
	f.Cleanup(Stop)
	whole := mustGet(f, "/rest/config")
	f.Add("PUT", "/rest/config", whole)
	f.Add("PATCH", "/rest/config/gui", []byte(`{"theme": "black"}`))
	f.Add("PATCH", "/rest/config/gui", []byte(`{"user": "", "password": ""}`))
	f.Add("PUT", "/rest/config/gui", []byte(`{"enabled": true, "address": "0.0.0.0:8384", "user": "", "password": ""}`))
	f.Add("PATCH", "/rest/config/options", []byte(`{"startBrowser": true}`))
	f.Add("PUT", "/rest/config", []byte(`{"gui": {"enabled": false}}`))

	f.Fuzz(func(t *testing.T, method, path string, body []byte) {
		switch method + " " + path {
		case "PUT /rest/config", "PATCH /rest/config/gui", "PUT /rest/config/gui", "PATCH /rest/config/options":
		default:
			return
		}
		do(t, method, path, body, true)
		// A GUI change restarts the listener; wait until it answers again.
		var code int
		var in bool
		for deadline := time.Now().Add(10 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
			if code, in = basicAuth(t, fuzzUser, fuzzPassword); code != 0 {
				break
			}
		}
		if code != http.StatusOK || !in {
			t.Fatalf("%s %s %q: app login refused (HTTP %d)", method, path, body, code)
		}
		if _, in := basicAuth(t, fuzzUser, "wrong"); in {
			t.Fatalf("%s %s %q: wrong password let in", method, path, body)
		}
		if c := do(t, http.MethodGet, "/rest/system/status", nil, false); c == http.StatusOK {
			t.Fatalf("%s %s %q: REST open without credentials", method, path, body)
		}
	})
}

// t0Home returns a home dir the core has initialised once.
func t0Home(tb testing.TB) string {
	home := tb.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	early := suture.New("seed", svcutil.SpecWithDebugLogger())
	done := early.ServeBackground(ctx)
	defer func() { cancel(); <-done }()
	if _, _, _, err := prepare(early, params{home, fuzzAPIKey, fuzzUser, fuzzPassword, fuzzAddr}); err != nil {
		tb.Fatal(err)
	}
	return home
}

func mustGet(tb testing.TB, path string) []byte {
	req, _ := http.NewRequest(http.MethodGet, "http://"+GuiAddress()+path, nil)
	req.Header.Set("X-API-Key", fuzzAPIKey)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		tb.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return b
}

// do sends a request, with the API key when auth is set, and returns the status, 0 when the listener is down.
func do(t *testing.T, method, path string, body []byte, auth bool) int {
	req, err := http.NewRequest(method, "http://"+GuiAddress()+path, strings.NewReader(string(body)))
	if err != nil {
		return 0
	}
	if auth {
		req.Header.Set("X-API-Key", fuzzAPIKey)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0
	}
	resp.Body.Close()
	return resp.StatusCode
}

// basicAuth returns the status of the app's way in, Basic auth on the page
// load, and whether that started a session. "/" is served to anyone (it is
// the login page), so only the session cookie shows the login was accepted.
func basicAuth(t *testing.T, user, password string) (int, bool) {
	req, _ := http.NewRequest(http.MethodGet, "http://"+GuiAddress()+"/", nil)
	req.SetBasicAuth(user, password)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, false
	}
	resp.Body.Close()
	for _, c := range resp.Cookies() {
		if strings.HasPrefix(c.Name, "sessionid-") {
			return resp.StatusCode, true
		}
	}
	return resp.StatusCode, false
}

// Patterns CheckIgnores accepts load without error once the core has written
// them, which is what the folder does next.
func FuzzCheckIgnores(f *testing.F) {
	for _, s := range []string{
		"", "!", "(?i)", "[", "a[b", "*.tmp\n!keep.tmp", "(?d).DS_Store", "// note\n/photos/**",
		"#include other.txt", "#include missing.txt", "{a,b", "**/[z-a]", "a\x00b", "\\", "#escape=x\nfoo",
	} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, patterns string) {
		dir := t.TempDir()
		if err := os.WriteFile(filepath.Join(dir, "other.txt"), []byte("*.bak\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		if CheckIgnores(dir, patterns) != "" {
			return
		}
		// As the app sends them: one line per element, trailing blanks dropped.
		lines := strings.Split(patterns, "\n")
		folder := fs.NewFilesystem(fs.FilesystemTypeBasic, dir)
		if err := ignore.WriteIgnores(folder, ".stignore", lines); err != nil {
			return // not a pattern problem (e.g. a name the OS refuses)
		}
		if err := ignore.New(folder).Load(".stignore"); err != nil {
			t.Fatalf("accepted %q, but the folder fails on it: %v", patterns, err)
		}
	})
}
