// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"bytes"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"testing"

	"golang.org/x/sys/unix"
)

func TestFilterLogKeepsWarningsAndErrors(t *testing.T) {
	long := strings.Repeat("x", 100_000)
	in := strings.Join([]string{
		"2026-10-28 09:30:00 INF Connected to device (device=ABC address=192.0.2.1:22000)",
		"2026-10-28 09:30:01 WRN Folder path missing (folder=photos)",
		"2026-10-28 09:30:02 DBG detail",
		"2026-10-28 09:30:03 ERR Failed to start",
		"2026-10-28 09:30:04 INF+2 also chatty",
		"not a log line",
		"",
		"2026-10-28 09:30:05 INF " + long,
		"2026-10-28 09:30:06 WRN " + long,
		"2026-10-28 09:30:07 INF last",
	}, "\n") + "\n"
	want := strings.Join([]string{
		"2026-10-28 09:30:01 WRN Folder path missing (folder=photos)",
		"2026-10-28 09:30:03 ERR Failed to start",
		"not a log line",
		"",
		"2026-10-28 09:30:06 WRN " + long,
	}, "\n") + "\n"

	var quiet bytes.Buffer
	filterLog(strings.NewReader(in), &quiet, func() bool { return false })
	if quiet.String() != want {
		t.Errorf("quiet output:\n%.300s\nwant:\n%.300s", quiet.String(), want)
	}
	var verbose bytes.Buffer
	filterLog(strings.NewReader(in), &verbose, func() bool { return true })
	if verbose.String() != in {
		t.Errorf("verbose output differs from the input")
	}
}

// The filter must drain whatever comes, or the core's log calls would block.
func TestFilterLogNeverStopsReading(t *testing.T) {
	r, w := io.Pipe()
	done := make(chan struct{})
	go func() { filterLog(r, io.Discard, func() bool { return false }); close(done) }()
	line := []byte("2026-10-28 09:30:00 INF " + strings.Repeat("y", 70_000) + "\n")
	for range 200 {
		if _, err := w.Write(line); err != nil {
			t.Fatal(err)
		}
	}
	w.Close()
	<-done
}

// The core's own logger, through the real fd 1 swap, in a child process so
// this test binary's stdout stays as it is. This also notices a change in the
// core's line format on a Syncthing bump.
func TestPipeStdoutWithTheCoresLogger(t *testing.T) {
	if os.Getenv("STBRIDGE_LOG_CHILD") == "1" {
		done, err := pipeStdout()
		if err != nil {
			t.Fatal(err)
		}
		slog.Info("info-line-secret")
		slog.Warn("warn-line")
		slog.Error("error-line")
		os.Stdout.WriteString("plain-line\n")
		unix.Close(1)
		<-done
		return
	}
	cmd := exec.Command(os.Args[0], "-test.run=^TestPipeStdoutWithTheCoresLogger$")
	cmd.Env = append(os.Environ(), "STBRIDGE_LOG_CHILD=1")
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("child: %v\n%s", err, out)
	}
	got := string(out)
	for _, s := range []string{"WRN warn-line", "ERR error-line", "plain-line"} {
		if !strings.Contains(got, s) {
			t.Errorf("missing %q in:\n%s", s, got)
		}
	}
	if strings.Contains(got, "info-line-secret") {
		t.Errorf("INFO line reached stdout:\n%s", got)
	}
}
