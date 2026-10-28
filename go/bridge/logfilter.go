// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"bufio"
	"bytes"
	"io"
	"os"
	"runtime"
	"sync"
	"sync/atomic"

	"golang.org/x/sys/unix"
)

// The core logs to stdout, which gomobile copies to logcat. Its INFO lines name
// devices, addresses and folder paths, and logcat ends up in bug reports, so
// only warnings and errors go there unless VerboseLog asks for everything
// (debug builds). The full log stays in the core's memory, where the web GUI's
// Logs view reads it. Stderr (Go panics) is left alone.

var (
	verboseLog atomic.Bool
	logOnce    sync.Once
)

// VerboseLog sends the core's whole log to logcat (on) or only its warnings
// and errors (off, the default).
func VerboseLog(on bool) {
	verboseLog.Store(on)
}

// filterStdout puts filterLog between the core and logcat, once per process.
// Only on Android: on a host, stdout is the terminal or the test runner's.
func filterStdout() {
	if runtime.GOOS != "android" {
		return
	}
	logOnce.Do(func() { _, _ = pipeStdout() })
}

// pipeStdout points fd 1 at a pipe drained by filterLog into what fd 1 was.
// done closes once fd 1 is closed and everything written has passed. On any
// error fd 1 stays as it was.
func pipeStdout() (done <-chan struct{}, err error) {
	orig, err := unix.Dup(1)
	if err != nil {
		return nil, err
	}
	var p [2]int
	if err := unix.Pipe2(p[:], unix.O_CLOEXEC); err != nil {
		unix.Close(orig)
		return nil, err
	}
	if err := unix.Dup3(p[1], 1, 0); err != nil {
		unix.Close(orig)
		unix.Close(p[0])
		unix.Close(p[1])
		return nil, err
	}
	unix.Close(p[1])
	ch := make(chan struct{})
	go func() {
		filterLog(os.NewFile(uintptr(p[0]), "core log"), os.NewFile(uintptr(orig), "logcat"), verboseLog.Load)
		close(ch)
	}()
	return ch, nil
}

// filterLog copies r to w, leaving out the core's DBG and INF lines unless
// verbose says otherwise. It never stops reading: a full pipe would block
// every log call in the core. Lines of any length pass in pieces.
func filterLog(r io.Reader, w io.Writer, verbose func() bool) {
	br := bufio.NewReaderSize(r, 4096)
	keep, start := true, true
	for {
		chunk, isPrefix, err := br.ReadLine()
		if err != nil {
			return
		}
		if start {
			keep = verbose() || !chatty(chunk)
		}
		if keep {
			_, _ = w.Write(chunk)
			if !isPrefix {
				_, _ = w.Write([]byte{'\n'})
			}
		}
		start = !isPrefix
	}
}

// chatty reports whether line is a core log line below WARN. The core writes
// "2006-01-02 15:04:05 INF message" (internal/slogutil DefaultLineFormat);
// anything else passes.
func chatty(line []byte) bool {
	const stamp = len("2006-01-02 15:04:05 ")
	if len(line) < stamp+3 || line[4] != '-' || line[7] != '-' || line[10] != ' ' || line[13] != ':' || line[16] != ':' || line[19] != ' ' {
		return false
	}
	level := line[stamp : stamp+3]
	return bytes.Equal(level, []byte("INF")) || bytes.Equal(level, []byte("DBG"))
}
