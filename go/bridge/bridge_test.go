package stbridge

import (
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

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
	req, _ := http.NewRequest(http.MethodGet, "http://"+GuiAddress()+"/", nil)
	req.SetBasicAuth("android", "secret")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GUI login no longer works: %d", resp.StatusCode)
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
