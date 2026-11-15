// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"fmt"
	"log/slog"
	"math"
	"runtime/debug"
	"runtime/metrics"

	"golang.org/x/sys/unix"
)

// MinMemoryLimit is the lowest soft memory limit SetMemoryLimit takes, in
// bytes (256 MB). Below it the core would collect garbage most of the time.
const MinMemoryLimit = 256_000_000

// SetMemoryLimit sets the Go runtime's soft memory limit in bytes, at once and
// for the whole process (the core need not run). 0 is no limit. As the memory
// Go holds nears the limit, it collects garbage more often (at most about half
// the CPU); it goes past the limit rather than fail when the live data needs
// more. It covers Go's own memory only, not SQLite's or the app's.
func SetMemoryLimit(bytes int64) error {
	if err := memoryLimitError(bytes, totalRAM()); err != nil {
		return err
	}
	if bytes == 0 {
		bytes = math.MaxInt64 // the runtime's "no limit"
	}
	debug.SetMemoryLimit(bytes)
	slog.Info("Go memory limit set", "bytes", bytes) // for tests on a phone: logcat shows what the app asked
	return nil
}

// memoryLimitError says why bytes is not a limit SetMemoryLimit takes on a
// phone with ram bytes of memory (0 when unknown, then no upper bound).
func memoryLimitError(bytes, ram int64) error {
	switch {
	case bytes == 0:
		return nil
	case bytes < MinMemoryLimit:
		return fmt.Errorf("memory limit %d bytes: at least %d, or 0 for no limit", bytes, MinMemoryLimit)
	case ram > 0 && bytes > ram:
		return fmt.Errorf("memory limit %d bytes: more than the phone's %d", bytes, ram)
	}
	return nil
}

// CoreMemory is the memory the Go runtime holds from the system now, in
// bytes: what the soft memory limit counts.
func CoreMemory() int64 {
	s := []metrics.Sample{{Name: "/memory/classes/total:bytes"}, {Name: "/memory/classes/heap/released:bytes"}}
	metrics.Read(s)
	return int64(s[0].Value.Uint64() - s[1].Value.Uint64())
}

// FreeOSMemory collects garbage and gives the memory it frees back to the
// system at once, instead of over the next minutes.
func FreeOSMemory() {
	debug.FreeOSMemory()
}

// totalRAM is the phone's memory in bytes, or 0 when the system does not say.
func totalRAM() int64 {
	var si unix.Sysinfo_t
	if unix.Sysinfo(&si) != nil {
		return 0
	}
	return int64(si.Totalram) * int64(si.Unit)
}
