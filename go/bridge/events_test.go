package stbridge

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/syncthing/syncthing/lib/events"
)

// The app asks the core only for the event types in testdata/follow-events.json
// (FOLLOW_EVENTS in core/Models.kt; SyncStatusFollowTest reads the same file). The
// core leaves a name it does not know out of the subscription without an error, so
// after a rename upstream the app would wait for an event that never comes.
func TestFollowEventsContract(t *testing.T) {
	b, err := os.ReadFile(filepath.Join("testdata", "follow-events.json"))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	if err := json.Unmarshal(b, &names); err != nil {
		t.Fatal(err)
	}
	if len(names) == 0 {
		t.Fatal("no names")
	}
	for _, name := range names {
		typ := events.UnmarshalEventType(name)
		if typ == 0 {
			t.Errorf("the core has no event type %q", name)
		} else if got := typ.String(); got != name {
			t.Errorf("%q is the core's %q", name, got)
		}
	}
}
