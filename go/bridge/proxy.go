// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/syncthing/syncthing/lib/dialer"
)

// proxyURL and proxyOnly are what the core connects through from its next
// start; nil is a direct connection. Guarded by mu.
var (
	proxyURL  *url.URL
	proxyOnly bool
)

// SetProxy sets the proxy the core connects through from its next start (a
// running core keeps its connections until it restarts). scheme is "" (no
// proxy), "socks5" or "http"; user and password are optional. With only,
// nothing goes around the proxy: no direct fallback, no QUIC, no NAT port
// mapping. The local network is always reached directly.
func SetProxy(scheme, host string, port int, user, password string, only bool) error {
	u, err := proxyFrom(scheme, host, port, user, password)
	if err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	proxyURL, proxyOnly = u, only && u != nil
	return nil
}

// applyProxyLocked hands the proxy to the core's dialer; the caller holds mu.
func applyProxyLocked() error {
	return dialer.SetProxy(proxyURL, proxyOnly)
}

// proxyFrom checks the app's proxy fields the way the core and the proxy
// protocols need them (ui/Validation.kt mirrors it), so a bad value fails
// here instead of at every dial.
func proxyFrom(scheme, host string, port int, user, password string) (*url.URL, error) {
	switch scheme {
	case "":
		return nil, nil
	case "socks5", "http":
	default:
		return nil, fmt.Errorf("proxy type %q: use socks5 or http", scheme)
	}
	if err := checkProxyHost(host); err != nil {
		return nil, err
	}
	if port < 1 || port > 65535 {
		return nil, fmt.Errorf("proxy port %d: use 1 to 65535", port)
	}
	u := &url.URL{Scheme: scheme, Host: net.JoinHostPort(host, strconv.Itoa(port))}
	switch {
	case user == "" && password != "":
		return nil, errors.New("proxy password without a user name")
	case user != "":
		// SOCKS5 user/password auth (RFC 1929) carries at most 255 bytes each.
		for _, f := range []struct{ name, v string }{{"user name", user}, {"password", password}} {
			if len(f.v) > 255 || !utf8.ValidString(f.v) || strings.IndexFunc(f.v, unicode.IsControl) >= 0 {
				return nil, fmt.Errorf("proxy %s: at most 255 bytes, no control characters", f.name)
			}
		}
		u.User = url.UserPassword(user, password)
	}
	return u, nil
}

// checkProxyHost accepts an IP address or a DNS name (RFC 1123 labels).
func checkProxyHost(host string) error {
	if net.ParseIP(host) != nil {
		return nil
	}
	bad := fmt.Errorf("proxy host %q: use an IP address or a host name", host)
	if host == "" || len(host) > 253 {
		return bad
	}
	labels := strings.Split(strings.TrimSuffix(host, "."), ".")
	if strings.Trim(labels[len(labels)-1], "0123456789") == "" {
		return bad // all digits: a mistyped IPv4 address, not a name
	}
	for _, label := range labels {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return bad
		}
		for _, r := range label {
			if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-') {
				return bad
			}
		}
	}
	return nil
}
