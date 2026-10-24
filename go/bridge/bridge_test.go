package stbridge

import (
	"net/http"
	"testing"
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
