// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

// Package stbridge runs the Syncthing core in-process for the Android app.
//
// The surface is deliberately tiny: start, stop and identity. Everything else
// goes through Syncthing's REST API, which is stable across releases, so
// upgrading the core rarely touches Kotlin code.
package stbridge

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"net"
	"os"
	"regexp"
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

// params is what the core was started with, kept for restarts in place.
type params struct {
	homeDir, apiKey, guiUser, guiPassword, addr string
}

// Start launches Syncthing with its config and database under homeDir and
// returns once the REST API is ready. The GUI/REST listener is bound to a
// free loopback port (see GuiAddress). apiKey is accepted in addition to the
// one in config. guiUser/guiPassword protect the web GUI, since other apps
// on the device can reach loopback; only the app knows them, and config
// changes that touch them are refused (see guiGuard).
func Start(homeDir, apiKey, guiUser, guiPassword string) error {
	mu.Lock()
	defer mu.Unlock()
	if app != nil {
		return errors.New("already running")
	}
	filterStdout()
	addr, err := freeLoopbackAddr()
	if err != nil {
		return err
	}
	return startLocked(params{homeDir, apiKey, guiUser, guiPassword, addr})
}

// startLocked starts the core; the caller holds mu.
func startLocked(p params) error {
	ctx, cncl := context.WithCancel(context.Background())
	early := suture.New("early", svcutil.SpecWithDebugLogger())
	earlyDone := early.ServeBackground(ctx)

	cert, cfg, evLogger, err := prepare(early, p)
	if err != nil {
		cncl()
		return err
	}

	if err := applyProxyLocked(); err != nil {
		cncl()
		return fmt.Errorf("proxy: %w", err)
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
	guiAddr = p.addr
	go watch(a, p)
	return nil
}

// prepare sets up homeDir and loads the identity and config the core starts
// with, with the GUI locked to the app's login. The config and event logger
// run under early.
func prepare(early *suture.Supervisor, p params) (tls.Certificate, config.Wrapper, events.Logger, error) {
	for _, s := range []string{p.apiKey, p.guiUser, p.guiPassword} {
		if !token.MatchString(s) {
			return tls.Certificate{}, nil, nil, errors.New("apiKey, guiUser and guiPassword must be 1 to 72 characters of A-Z, a-z, 0-9, - and _")
		}
	}
	homeDir := p.homeDir
	// Env overrides are read by lib/config on every access.
	os.Setenv("STGUIADDRESS", p.addr)
	os.Setenv("STGUIAPIKEY", p.apiKey)
	os.Setenv("STNOUPGRADE", "1")

	if err := locations.SetBaseDir(locations.ConfigBaseDir, homeDir); err != nil {
		return tls.Certificate{}, nil, nil, err
	}
	if err := locations.SetBaseDir(locations.DataBaseDir, homeDir); err != nil {
		return tls.Certificate{}, nil, nil, err
	}
	if err := syncthing.EnsureDir(homeDir, 0o700); err != nil {
		return tls.Certificate{}, nil, nil, err
	}

	slog.Info(build.LongVersion) //nolint:sloglint

	cert, err := loadOrGenerateCertificate(locations.Get(locations.CertFile), locations.Get(locations.KeyFile))
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf("certificate: %w", err)
	}

	evLogger := events.NewLogger()
	early.Add(evLogger)

	cfg, err := syncthing.LoadConfigAtStartup(locations.Get(locations.ConfigFile), cert, evLogger, false, false)
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf("config: %w", err)
	}
	early.Add(cfg)
	if err := applyAndroidDefaults(cfg, p.apiKey, p.guiUser, p.guiPassword); err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf("config defaults: %w", err)
	}
	cfg.Subscribe(guiGuard{})
	return cert, cfg, evLogger, nil
}

// token is what the app's credentials look like (Settings.randomToken): safe
// in an HTTP header and in Basic auth (no ":"), never taken for a bcrypt hash
// (no "$"), and within the 72 bytes bcrypt reads.
var token = regexp.MustCompile(`^[A-Za-z0-9_-]{1,72}$`)

// loadOrGenerateCertificate loads this device's identity, or creates one when
// there is none yet. Unlike upstream's LoadOrGenerateCertificate it never
// replaces files it can't read: a damaged or foreign cert or key would
// otherwise turn the phone silently into a new device its peers don't know.
func loadOrGenerateCertificate(certFile, keyFile string) (tls.Certificate, error) {
	_, certErr := os.Stat(certFile)
	_, keyErr := os.Stat(keyFile)
	if errors.Is(certErr, fs.ErrNotExist) && errors.Is(keyErr, fs.ErrNotExist) {
		return syncthing.GenerateCertificate(certFile, keyFile)
	}
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return tls.Certificate{}, fmt.Errorf("this device's identity (cert.pem, key.pem) is unreadable: %w", err)
	}
	return cert, nil
}

// watch handles the core exiting on its own. A restart asked for in the web
// GUI ("Restart Needed" after some settings, or a database reset) starts it
// again in place, on the same address, so the app's client and the open web
// GUI carry on. mu is held throughout, so IsRunning never sees a gap.
func watch(a *syncthing.App, p params) {
	status := a.Wait()
	mu.Lock()
	defer mu.Unlock()
	if app != a {
		return // Stop was called
	}
	c, done := cancel, earlyEnd
	app, cancel, guiAddr = nil, nil, ""
	c()
	<-done
	switch status {
	case svcutil.ExitRestart:
		if err := startLocked(p); err != nil {
			exitErr = fmt.Errorf("restart: %w", err)
		}
	case svcutil.ExitError:
		exitErr = a.Error()
	}
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

// applyAndroidDefaults enforces settings the app relies on. The core accepts
// the config's API key next to STGUIAPIKEY, so the config gets the app's key
// too: a key from elsewhere (an imported config) must not stay valid.
func applyAndroidDefaults(cfg config.Wrapper, apiKey, user, password string) error {
	gui := cfg.GUI()
	needPassword := gui.User != user || gui.CompareHashedPassword(password) != nil
	if !needPassword && gui.APIKey == apiKey && lockOf(gui) == lockOf(locked(gui)) && !cfg.Options().StartBrowser {
		return nil
	}
	var hashErr error
	w, err := cfg.Modify(func(c *config.Configuration) {
		c.GUI = locked(c.GUI)
		c.GUI.APIKey = apiKey
		c.Options.StartBrowser = false
		if needPassword {
			c.GUI.User = user
			hashErr = c.GUI.SetPassword(password)
		}
	})
	if err != nil {
		return err
	}
	w.Wait()
	if hashErr != nil {
		// Unreachable with a valid token; fail closed rather than run with the old login.
		return fmt.Errorf("GUI password: %w", hashErr)
	}
	// Modify only changes the running config; saving keeps config.xml locked
	// too, and spares the next start a bcrypt hash.
	return cfg.Save()
}

// locked returns gui with every setting that guards access set the app's way.
func locked(gui config.GUIConfiguration) config.GUIConfiguration {
	gui.Enabled = true
	gui.RawUseTLS = false // loopback only; WebView would reject the self-signed cert
	gui.AuthMode = config.AuthModeStatic
	gui.InsecureAdminAccess = false
	gui.InsecureSkipHostCheck = false
	gui.MetricsWithoutAuth = false
	gui.InsecureAllowFrameLoading = false // other origins framing the GUI
	return gui
}

// guiAccess is the part of the GUI config that decides who can get in.
type guiAccess struct {
	enabled, tls, insecureAdmin, skipHostCheck, openMetrics, framing bool
	user, password, apiKey                                           string
	authMode                                                         config.AuthMode
}

func lockOf(g config.GUIConfiguration) guiAccess {
	return guiAccess{
		g.Enabled, g.RawUseTLS, g.InsecureAdminAccess, g.InsecureSkipHostCheck, g.MetricsWithoutAuth, g.InsecureAllowFrameLoading,
		g.User, g.Password, g.APIKey, g.AuthMode,
	}
}

// guiGuard refuses config changes, such as a save in the web GUI's settings,
// that would change the GUI login or API key, or open the GUI to other apps on
// the device.
type guiGuard struct{}

var errGUILocked = errors.New("the GUI login and access settings are managed by MySyncthing")

func (guiGuard) VerifyConfiguration(from, to config.Configuration) error {
	if lockOf(from.GUI) != lockOf(to.GUI) {
		return errGUILocked
	}
	return nil
}

func (guiGuard) CommitConfiguration(_, _ config.Configuration) bool { return true }

func (guiGuard) String() string { return "guiGuard" }

func freeLoopbackAddr() (string, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	defer l.Close()
	return l.Addr().String(), nil
}
