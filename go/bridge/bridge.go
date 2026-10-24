// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

// Package stbridge runs the Syncthing core in-process for the Android app.
//
// The surface is deliberately tiny: start, stop and identity. Everything else
// goes through Syncthing's REST API, which is stable across releases, so
// upgrading the core rarely touches Kotlin code.
package stbridge

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"sync"
	"time"

	"github.com/thejerf/suture/v4"

	"github.com/syncthing/syncthing/lib/build"
	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/events"
	"github.com/syncthing/syncthing/lib/locations"
	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/svcutil"
	"github.com/syncthing/syncthing/lib/syncthing"
)

const (
	deleteRetention     = 10920 * time.Hour // upstream default
	maintenanceInterval = 8 * time.Hour     // upstream default
)

var (
	mu       sync.Mutex
	app      *syncthing.App
	cancel   context.CancelFunc
	deviceID string
	guiAddr  string
	exitErr  error
	earlyEnd <-chan error // closed when the early supervisor (events, config) has stopped
)

// Start launches Syncthing with its config and database under homeDir and
// returns once the REST API is ready. The GUI/REST listener is bound to a
// free loopback port (see GuiAddress). apiKey is accepted in addition to the
// one in config. guiUser/guiPassword protect the web GUI, since other apps
// on the device can reach loopback.
func Start(homeDir, apiKey, guiUser, guiPassword string) error {
	mu.Lock()
	defer mu.Unlock()
	if app != nil {
		return errors.New("already running")
	}
	if apiKey == "" || guiUser == "" || guiPassword == "" {
		return errors.New("apiKey, guiUser and guiPassword are required")
	}

	addr, err := freeLoopbackAddr()
	if err != nil {
		return err
	}
	// Env overrides are read by lib/config on every access.
	os.Setenv("STGUIADDRESS", addr)
	os.Setenv("STGUIAPIKEY", apiKey)
	os.Setenv("STNOUPGRADE", "1")

	if err := locations.SetBaseDir(locations.ConfigBaseDir, homeDir); err != nil {
		return err
	}
	if err := locations.SetBaseDir(locations.DataBaseDir, homeDir); err != nil {
		return err
	}
	if err := syncthing.EnsureDir(homeDir, 0o700); err != nil {
		return err
	}

	slog.Info(build.LongVersion) //nolint:sloglint

	cert, err := syncthing.LoadOrGenerateCertificate(locations.Get(locations.CertFile), locations.Get(locations.KeyFile))
	if err != nil {
		return fmt.Errorf("certificate: %w", err)
	}

	ctx, cncl := context.WithCancel(context.Background())
	early := suture.New("early", svcutil.SpecWithDebugLogger())
	earlyDone := early.ServeBackground(ctx)

	evLogger := events.NewLogger()
	early.Add(evLogger)

	cfg, err := syncthing.LoadConfigAtStartup(locations.Get(locations.ConfigFile), cert, evLogger, false, false)
	if err != nil {
		cncl()
		return fmt.Errorf("config: %w", err)
	}
	early.Add(cfg)
	if err := applyAndroidDefaults(cfg, guiUser, guiPassword); err != nil {
		cncl()
		return fmt.Errorf("config defaults: %w", err)
	}

	sdb, err := syncthing.OpenDatabase(locations.Get(locations.Database), deleteRetention)
	if err != nil {
		cncl()
		return fmt.Errorf("database: %w", err)
	}

	a, err := syncthing.New(cfg, sdb, evLogger, cert, syncthing.Options{
		NoUpgrade:             true,
		DBMaintenanceInterval: maintenanceInterval,
	})
	if err != nil {
		cncl()
		return fmt.Errorf("init: %w", err)
	}
	if err := a.Start(); err != nil {
		cncl()
		return fmt.Errorf("start: %w", err)
	}

	app, cancel, exitErr, earlyEnd = a, cncl, nil, earlyDone
	deviceID = protocol.NewDeviceID(cert.Certificate[0]).String()
	guiAddr = addr

	go func() {
		status := a.Wait()
		mu.Lock()
		defer mu.Unlock()
		if app == a {
			if status == svcutil.ExitError {
				exitErr = a.Error()
			}
			cancel()
			app, cancel, guiAddr = nil, nil, ""
		}
	}()
	return nil
}

// Stop shuts Syncthing down and blocks until it has exited.
func Stop() {
	mu.Lock()
	a, c, done := app, cancel, earlyEnd
	app, cancel, guiAddr = nil, nil, ""
	mu.Unlock()
	if a == nil {
		return
	}
	a.Stop(svcutil.ExitSuccess)
	a.Wait()
	c()
	<-done // config writes finish before the caller may touch homeDir
}

// IsRunning reports whether the core is running.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return app != nil
}

// LastError returns the error the core last exited with, or "".
func LastError() string {
	mu.Lock()
	defer mu.Unlock()
	if exitErr == nil {
		return ""
	}
	return exitErr.Error()
}

// GuiAddress returns host:port of the REST API/GUI, or "" when stopped.
func GuiAddress() string {
	mu.Lock()
	defer mu.Unlock()
	return guiAddr
}

// DeviceID returns this device's ID once started at least once.
func DeviceID() string {
	mu.Lock()
	defer mu.Unlock()
	return deviceID
}

// Version returns the Syncthing core version.
func Version() string {
	return build.Version
}

// applyAndroidDefaults enforces settings the app relies on.
func applyAndroidDefaults(cfg config.Wrapper, user, password string) error {
	gui := cfg.GUI()
	needPassword := gui.User != user || gui.CompareHashedPassword(password) != nil
	opts := cfg.Options()
	if !needPassword && gui.Enabled && !opts.StartBrowser && !gui.UseTLS() {
		return nil
	}
	w, err := cfg.Modify(func(c *config.Configuration) {
		c.GUI.Enabled = true
		c.GUI.RawUseTLS = false // loopback only; WebView would reject the self-signed cert
		c.Options.StartBrowser = false
		if needPassword {
			c.GUI.User = user
			_ = c.GUI.SetPassword(password)
		}
	})
	if err != nil {
		return err
	}
	w.Wait()
	return nil
}

func freeLoopbackAddr() (string, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	defer l.Close()
	return l.Addr().String(), nil
}
