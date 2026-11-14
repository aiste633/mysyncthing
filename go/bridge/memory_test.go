// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"math"
	"runtime/debug"
	"testing"
)

func TestMemoryLimitBounds(t *testing.T) {
	const ram = 4_000_000_000
	for _, c := range []struct {
		bytes int64
		ok    bool
	}{
		{0, true}, {-1, false}, {math.MinInt64, false}, {1, false}, {MinMemoryLimit - 1, false},
		{MinMemoryLimit, true}, {ram, true}, {ram + 1, false}, {math.MaxInt64, false},
	} {
		if err := memoryLimitError(c.bytes, ram); (err == nil) != c.ok {
			t.Errorf("%d: %v, want ok=%v", c.bytes, err, c.ok)
		}
	}
	if err := memoryLimitError(math.MaxInt64, 0); err != nil {
		t.Errorf("unknown RAM must not bound the limit: %v", err)
	}
}

func TestSetMemoryLimit(t *testing.T) {
	before := debug.SetMemoryLimit(-1)
	defer debug.SetMemoryLimit(before)
	if err := SetMemoryLimit(MinMemoryLimit); err != nil {
		t.Fatal(err)
	}
	if got := debug.SetMemoryLimit(-1); got != MinMemoryLimit {
		t.Errorf("limit %d, want %d", got, MinMemoryLimit)
	}
	if err := SetMemoryLimit(0); err != nil {
		t.Fatal(err)
	}
	if got := debug.SetMemoryLimit(-1); got != math.MaxInt64 {
		t.Errorf("0 left the limit at %d, want none", got)
	}
	if err := SetMemoryLimit(1); err == nil {
		t.Error("1 byte was taken")
	}
	if got := debug.SetMemoryLimit(-1); got != math.MaxInt64 {
		t.Errorf("a refused limit changed it to %d", got)
	}
}

func TestCoreMemory(t *testing.T) {
	keep := make([]byte, 64<<20)
	keep[len(keep)-1] = 1
	if m := CoreMemory(); m < int64(len(keep)) || m > totalRAM() {
		t.Errorf("CoreMemory %d with %d bytes live", m, len(keep))
	}
	FreeOSMemory()
	_ = keep[0]
}

func FuzzMemoryLimitError(f *testing.F) {
	for _, s := range []int64{0, -1, 1, MinMemoryLimit, 4_000_000_000, math.MaxInt64} {
		f.Add(s, int64(4_000_000_000))
	}
	f.Fuzz(func(t *testing.T, bytes, ram int64) {
		err := memoryLimitError(bytes, ram)
		want := bytes == 0 || (bytes >= MinMemoryLimit && (ram <= 0 || bytes <= ram))
		if (err == nil) != want {
			t.Fatalf("memoryLimitError(%d, %d) = %v, want ok=%v", bytes, ram, err, want)
		}
	})
}
