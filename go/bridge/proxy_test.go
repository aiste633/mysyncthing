// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"math/rand"
	"net"
	"net/url"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"testing/quick"

	"github.com/syncthing/syncthing/lib/dialer"
)

func TestProxyFrom(t *testing.T) {
	ok := []struct {
		scheme, host   string
		port           int
		user, password string
		want           string
	}{
		{"", "ignored", 0, "", "", ""},
		{"socks5", "127.0.0.1", 9050, "", "", "socks5://127.0.0.1:9050"},
		{"socks5", "::1", 1080, "", "", "socks5" + "://[::1]:1080"},
		{"http", "proxy.example.org", 3128, "", "", "http://proxy.example.org:3128"},
		{"http", "proxy.example.org.", 1, "", "", "http" + "://proxy.example.org.:1"},
		{"socks5", "example.org", 65535, "me", "", "socks5://me:@example.org:65535"},
		{"socks5", "example.org", 1080, "a b", "p@1:/w?#rd", "socks5://a%20b:p%401%3A%2Fw%3F%23rd@example.org:1080"},
		{"socks5", "example.org", 1080, "ünï", "пароль", "socks5://%C3%BCn%C3%AF:%D0%BF%D0%B0%D1%80%D0%BE%D0%BB%D1%8C@example.org:1080"},
	}
	for _, c := range ok {
		u, err := proxyFrom(c.scheme, c.host, c.port, c.user, c.password)
		if err != nil {
			t.Errorf("%+v: %v", c, err)
			continue
		}
		if got := ""; u != nil {
			got = u.String()
			if got != c.want {
				t.Errorf("%+v: %s, want %s", c, got, c.want)
			}
		} else if c.want != "" {
			t.Errorf("%+v: no proxy, want %s", c, c.want)
		}
	}

	bad := []struct {
		scheme, host   string
		port           int
		user, password string
	}{
		{"socks4", "p", 1080, "", ""},
		{"https", "p", 443, "", ""},
		{"SOCKS5", "p", 1080, "", ""},
		{"socks5", "", 1080, "", ""},
		{"socks5", "p", 0, "", ""},
		{"socks5", "p", 65536, "", ""},
		{"socks5", "p", -1, "", ""},
		{"socks5", "proxy.example.org:1080", 1080, "", ""},
		{"socks5", "[::1]", 1080, "", ""},
		{"socks5", "me@example.org", 1080, "", ""},
		{"socks5", "p/x", 1080, "", ""},
		{"socks5", "p q", 1080, "", ""},
		{"socks5", " p", 1080, "", ""},
		{"socks5", "-p", 1080, "", ""},
		{"socks5", "p-", 1080, "", ""},
		{"socks5", "a..b", 1080, "", ""},
		{"socks5", ".", 1080, "", ""},
		{"socks5", "192.0.2.999", 1080, "", ""},
		{"socks5", "10.0.0", 1080, "", ""},
		{"socks5", "2001:db8::1%wlan0", 1080, "", ""},
		{"socks5", "prøxy", 1080, "", ""},
		{"socks5", strings.Repeat("a", 64), 1080, "", ""},
		{"socks5", strings.Repeat("a.", 127) + "aa", 1080, "", ""},
		{"socks5", "p", 1080, "", "secret"},
		{"socks5", "p", 1080, "me\n", ""},
		{"socks5", "p", 1080, "me", "a\x00b"},
		{"socks5", "p", 1080, strings.Repeat("u", 256), ""},
		{"socks5", "p", 1080, "me", strings.Repeat("p", 256)},
		{"socks5", "p", 1080, "me", "\xff"},
	}
	for _, c := range bad {
		if u, err := proxyFrom(c.scheme, c.host, c.port, c.user, c.password); err == nil {
			t.Errorf("%+v accepted as %v", c, u)
		}
	}
}

// proxyInput is a random set of proxy fields, mostly valid, with the usual
// typing mistakes mixed in.
type proxyInput struct {
	Scheme, Host   string
	Port           int
	User, Password string
}

func (proxyInput) Generate(r *rand.Rand, _ int) reflect.Value {
	pick := func(xs ...string) string { return xs[r.Intn(len(xs))] }
	runes := func(alphabet string, n int) string {
		a := []rune(alphabet)
		var b strings.Builder
		for range r.Intn(n + 1) {
			b.WriteRune(a[r.Intn(len(a))])
		}
		return b.String()
	}
	in := proxyInput{
		Scheme: pick("socks5", "http", "", "socks", "https", "Socks5"),
		Host: pick(
			"127.0.0.1", "::1", "198.51.100.20", "2001:db8::7",
			runes("abcdefghijklmnopqrstuvwxyz0123456789-", 12)+pick("", ".example", ".example.org", ".", "-", " "),
			runes("abc.-_:/@ []%0123456789", 20),
		),
		Port: pick2(r, r.Intn(65535)+1, r.Intn(200000)-100000),
	}
	if r.Intn(2) == 0 {
		in.User = runes("abcxyz0123 @:/?#%üп\t", 10)
		in.Password = runes("abcxyz0123 @:/?#%üп\x00", 300)
	}
	return reflect.ValueOf(in)
}

func pick2(r *rand.Rand, a, b int) int {
	if r.Intn(4) == 0 {
		return b
	}
	return a
}

// Property: what proxyFrom accepts reaches the dialer unchanged. The URL
// parses back to the same scheme, host, port, user and password, and the
// core's dialer takes it.
func TestProxyFromRoundTrip(t *testing.T) {
	t.Cleanup(func() { _ = dialer.SetProxy(nil, false) })
	accepted := 0
	prop := func(in proxyInput) bool {
		u, err := proxyFrom(in.Scheme, in.Host, in.Port, in.User, in.Password)
		if err != nil || u == nil {
			return true
		}
		accepted++
		back, err := url.Parse(u.String())
		if err != nil {
			t.Logf("%+v: %s does not parse: %v", in, u, err)
			return false
		}
		host, port, err := net.SplitHostPort(back.Host)
		pw, _ := back.User.Password()
		if err != nil || back.Scheme != in.Scheme || host != in.Host || port != strconv.Itoa(in.Port) ||
			back.User.Username() != in.User || pw != in.Password {
			t.Logf("%+v: came back as %#v", in, back)
			return false
		}
		if err := dialer.SetProxy(back, true); err != nil {
			t.Logf("%+v: dialer refused %s: %v", in, u, err)
			return false
		}
		return true
	}
	if err := quick.Check(prop, &quick.Config{MaxCount: 20000, Rand: rand.New(rand.NewSource(1))}); err != nil {
		t.Fatal(err)
	}
	if accepted < 1000 {
		t.Fatalf("only %d of 20000 inputs accepted: the generator misses the valid cases", accepted)
	}
}

func FuzzProxyFrom(f *testing.F) {
	f.Add("socks5", "127.0.0.1", 9050, "", "")
	f.Add("http", "proxy.example.org", 3128, "me", "p@1:/w")
	f.Add("socks5", "::1", 1080, "ü", "")
	f.Fuzz(func(t *testing.T, scheme, host string, port int, user, password string) {
		u, err := proxyFrom(scheme, host, port, user, password)
		if err != nil || u == nil {
			return
		}
		back, err := url.Parse(u.String())
		if err != nil || back.String() != u.String() {
			t.Fatalf("%s does not round-trip: %v", u, err)
		}
	})
}

func TestSetProxyReachesDialer(t *testing.T) {
	t.Cleanup(func() {
		_ = SetProxy("", "", 0, "", "", false)
		_ = dialer.SetProxy(nil, false)
	})
	if err := SetProxy("socks5", "127.0.0.1", 9050, "", "", true); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	err := applyProxyLocked()
	mu.Unlock()
	if err != nil || !dialer.NoFallback() {
		t.Fatalf("proxy-only not applied: %v", err)
	}
	if err := SetProxy("socks5", "127.0.0.1", 0, "", "", true); err == nil {
		t.Fatal("port 0 accepted")
	}
	// A refused value leaves the previous setting.
	mu.Lock()
	kept := proxyURL != nil && proxyOnly
	mu.Unlock()
	if !kept {
		t.Fatal("a refused proxy cleared the previous one")
	}
	// "Only through the proxy" means nothing without a proxy.
	if err := SetProxy("", "", 0, "", "", true); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	err = applyProxyLocked()
	mu.Unlock()
	if err != nil || dialer.NoFallback() {
		t.Fatalf("no proxy, but proxy-only still on: %v", err)
	}
}
