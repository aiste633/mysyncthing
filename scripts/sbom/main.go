// Command sbom writes the software bill of materials of the release APK: docs/sbom.cdx.json
// (CycloneDX 1.6) and the readable docs/sbom.md. Run it through scripts/sbom.sh, which
// also feeds it the Gradle side. With -verify it instead checks the Go modules in an SBOM
// against the build info Go embeds in a built libgojni.so.
//
// The output depends only on the repository: no timestamps, no serial number, sorted
// throughout. That is what lets CI regenerate it and fail when it drifts.
package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"debug/buildinfo"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	"maps"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strings"

	"github.com/google/licensecheck"
)

func main() {
	log.SetFlags(0)
	log.SetPrefix("sbom: ")
	repo := flag.String("repo", ".", "repository root")
	gradle := flag.String("gradle", "", "output of scripts/sbom/runtime-deps.init.gradle.kts")
	out := flag.String("out", "docs", "directory for sbom.cdx.json and sbom.md")
	verify := flag.String("verify", "", "check the Go modules in -out's SBOM against this libgojni.so")
	flag.Parse()

	if *verify != "" {
		if err := verifyBinary(filepath.Join(*out, "sbom.cdx.json"), *verify); err != nil {
			log.Fatal(err)
		}
		return
	}
	if *gradle == "" {
		log.Fatal("-gradle is required")
	}
	r := &run{repo: *repo, comps: map[string]*Component{}}
	r.app()
	r.maven(*gradle)
	r.golang()
	r.webGUI()
	r.buildTools()
	r.checkLicenses()
	if r.errs != nil {
		log.Fatal(errors.Join(r.errs...))
	}
	b := r.bom()
	js, err := json.MarshalIndent(b, "", "  ")
	check(err)
	check(os.WriteFile(filepath.Join(*out, "sbom.cdx.json"), append(js, '\n'), 0o644))
	check(os.WriteFile(filepath.Join(*out, "sbom.md"), markdown(b), 0o644))
}

// CycloneDX 1.6, only the parts used here.

type BOM struct {
	Format       string       `json:"bomFormat"`
	Spec         string       `json:"specVersion"`
	Version      int          `json:"version"`
	Metadata     Metadata     `json:"metadata"`
	Components   []*Component `json:"components"`
	Dependencies []Dependency `json:"dependencies"`
}

type Metadata struct {
	Tools     Tools      `json:"tools"`
	Component *Component `json:"component"`
}

type Tools struct {
	Components []*Component `json:"components"`
}

type Component struct {
	Type        string     `json:"type"`
	Ref         string     `json:"bom-ref"`
	Group       string     `json:"group,omitempty"`
	Name        string     `json:"name"`
	Version     string     `json:"version,omitempty"`
	Description string     `json:"description,omitempty"`
	Scope       string     `json:"scope,omitempty"`
	Hashes      []Hash     `json:"hashes,omitempty"`
	Licenses    []License  `json:"licenses,omitempty"`
	Purl        string     `json:"purl,omitempty"`
	ExtRefs     []ExtRef   `json:"externalReferences,omitempty"`
	Properties  []Property `json:"properties,omitempty"`

	section string   // sbom.md section
	deps    []string // bom-refs
}

type Hash struct {
	Alg     string `json:"alg"`
	Content string `json:"content"`
}

// License is a single SPDX id or, for more than one licence, an SPDX expression.
type License struct {
	License    *LicenseID `json:"license,omitempty"`
	Expression string     `json:"expression,omitempty"`
}

type LicenseID struct {
	ID string `json:"id"`
}

type ExtRef struct {
	Type string `json:"type"`
	URL  string `json:"url"`
}

type Property struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

type Dependency struct {
	Ref       string   `json:"ref"`
	DependsOn []string `json:"dependsOn,omitempty"`
}

const (
	secApp     = "App"
	secAndroid = "Android and Kotlin libraries"
	secGo      = "Go modules (Syncthing core)"
	secC       = "C code bundled in Go modules"
	secWeb     = "Web GUI (embedded in the core)"
	secBuild   = "Build tools (not shipped)"
	appRef     = "pkg:android/eu.stedb.mysyncthing"
	propPrefix = "mysyncthing:"
)

var sections = []string{secApp, secAndroid, secGo, secC, secWeb, secBuild}

type run struct {
	repo  string
	comps map[string]*Component // by bom-ref
	app_  *Component
	errs  []error
}

func (r *run) fail(format string, a ...any) { r.errs = append(r.errs, fmt.Errorf(format, a...)) }

func (r *run) add(c *Component) *Component {
	if c.Ref == "" {
		c.Ref = c.Purl
	}
	if _, dup := r.comps[c.Ref]; dup {
		r.fail("duplicate component %s", c.Ref)
	}
	r.comps[c.Ref] = c
	return c
}

func (r *run) path(p ...string) string { return filepath.Join(append([]string{r.repo}, p...)...) }

func (r *run) read(p ...string) string {
	b, err := os.ReadFile(r.path(p...))
	check(err)
	return string(b)
}

// find returns the first group of re in the file, or records an error.
func (r *run) find(re, file string) string {
	m := regexp.MustCompile(re).FindStringSubmatch(r.read(file))
	if m == nil {
		r.fail("%s: no match for %s", file, re)
		return ""
	}
	return m[1]
}

func (r *run) app() {
	version := r.find(`(?m)^\s*versionName = "(.+)"$`, "app/build.gradle.kts")
	r.app_ = &Component{
		Type: "application", Ref: appRef, Name: "MySyncthing", Version: version,
		Licenses: licenses(r.find(`(?m)^License: (.+)$`, "fdroid/eu.stedb.mysyncthing.yml")),
		Purl:     appRef + "@" + version,
		ExtRefs:  []ExtRef{{"vcs", "https://github.com/aiste633/mysyncthing"}},
		section:  secApp,
	}
}

// Maven components, from the Gradle init script's output.

type pom struct {
	Parent struct {
		Group    string `xml:"groupId"`
		Artifact string `xml:"artifactId"`
		Version  string `xml:"version"`
	} `xml:"parent"`
	Licenses []struct {
		Name string `xml:"name"`
		URL  string `xml:"url"`
	} `xml:"licenses>license"`
}

// POM licence names (lower case) to SPDX ids.
var pomLicenses = map[string]string{
	"apache-2.0":                               "Apache-2.0",
	"the apache license, version 2.0":          "Apache-2.0",
	"the apache software license, version 2.0": "Apache-2.0",
	"apache license, version 2.0":              "Apache-2.0",
	"bsd-3-clause":                             "BSD-3-Clause",
	"mit":                                      "MIT",
	"the mit license":                          "MIT",
}

func (r *run) maven(gradleOut string) {
	f, err := os.Open(gradleOut)
	check(err)
	defer f.Close()
	poms := map[string]*pom{}
	var shipped []string
	edges := map[string][]string{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		w := strings.Fields(sc.Text())
		switch {
		case len(w) == 2 && w[0] == "ship":
			shipped = append(shipped, w[1])
		case len(w) == 3 && w[0] == "dep":
			edges[w[1]] = append(edges[w[1]], w[2])
		case len(w) == 3 && w[0] == "pom":
			b, err := os.ReadFile(w[2])
			check(err)
			p := &pom{}
			if err := xml.Unmarshal(b, p); err != nil {
				r.fail("POM of %s: %v", w[1], err)
			}
			poms[w[1]] = p
		default:
			r.fail("unexpected Gradle output line %q", sc.Text())
		}
	}
	check(sc.Err())
	if len(shipped) == 0 {
		r.fail("Gradle listed no shipped components")
	}

	hashes := r.verifiedHashes()
	ref := map[string]string{}
	for _, gav := range shipped {
		g, a, v := splitGAV(gav)
		p := poms[gav]
		var lic []string
		for q := p; q != nil && len(lic) == 0; q = poms[q.Parent.Group+":"+q.Parent.Artifact+":"+q.Parent.Version] {
			for _, l := range q.Licenses {
				id, ok := pomLicenses[strings.ToLower(strings.TrimSpace(l.Name))]
				if !ok {
					r.fail("%s: unknown POM licence %q (%s): add it to pomLicenses", gav, l.Name, l.URL)
				}
				lic = append(lic, id)
			}
		}
		if p == nil {
			r.fail("%s: no POM", gav)
			continue
		}
		if len(lic) == 0 {
			r.fail("%s: no licence in its POM or parent POMs", gav)
		}
		c := &Component{Type: "library", Group: g, Name: a, Version: v,
			Licenses: licenses(strings.Join(lic, " AND ")), section: secAndroid}
		file, sum := hashes.main(g, a, v)
		if sum == "" {
			r.fail("%s: not exactly one .aar or .jar checksum in gradle/verification-metadata.xml", gav)
		}
		c.Purl = fmt.Sprintf("pkg:maven/%s/%s@%s", g, a, v)
		if strings.HasSuffix(file, ".aar") {
			c.Purl += "?type=aar"
		}
		c.Hashes = []Hash{{"SHA-256", sum}}
		ref[gav] = r.add(c).Ref
	}
	for from, tos := range edges {
		for _, to := range tos {
			if ref[to] == "" || (from != "root" && ref[from] == "") {
				r.fail("Gradle dependency %s -> %s between unlisted components", from, to)
				continue
			}
			if from == "root" {
				r.app_.deps = append(r.app_.deps, ref[to])
			} else {
				r.comps[ref[from]].deps = append(r.comps[ref[from]].deps, ref[to])
			}
		}
	}
}

func splitGAV(s string) (g, a, v string) {
	p := strings.SplitN(s, ":", 3)
	if len(p) != 3 {
		log.Fatalf("bad Maven coordinates %q", s)
	}
	return p[0], p[1], p[2]
}

type verified map[string]map[string]string // g:a:v -> file -> sha256

// verifiedHashes reads the SHA-256 checksums that Gradle checks every download against.
func (r *run) verifiedHashes() verified {
	var m struct {
		Components []struct {
			Group     string `xml:"group,attr"`
			Name      string `xml:"name,attr"`
			Version   string `xml:"version,attr"`
			Artifacts []struct {
				Name   string `xml:"name,attr"`
				SHA256 []struct {
					Value string `xml:"value,attr"`
				} `xml:"sha256"`
			} `xml:"artifact"`
		} `xml:"components>component"`
	}
	check(xml.Unmarshal([]byte(r.read("gradle/verification-metadata.xml")), &m))
	v := verified{}
	for _, c := range m.Components {
		files := map[string]string{}
		for _, a := range c.Artifacts {
			if len(a.SHA256) > 0 {
				files[a.Name] = a.SHA256[0].Value
			}
		}
		v[c.Group+":"+c.Name+":"+c.Version] = files
	}
	return v
}

// main returns the component's packaged artifact (its one .aar or .jar; Kotlin Multiplatform
// variants name it without the version) and its SHA-256.
func (v verified) main(g, a, ver string) (file, sum string) {
	n := 0
	for f, s := range v[g+":"+a+":"+ver] {
		if (strings.HasSuffix(f, ".aar") || strings.HasSuffix(f, ".jar")) &&
			!strings.HasSuffix(f, "-sources.jar") && !strings.HasSuffix(f, "-javadoc.jar") {
			file, sum = f, s
			n++
		}
	}
	if n != 1 {
		return "", ""
	}
	return file, sum
}

// Go modules: exactly the packages linked into the core, as gomobile builds it.

const (
	bridgeModule = "eu.stedb/mysyncthing/stbridge"
	stModule     = "github.com/syncthing/syncthing"
)

type goPackage struct {
	ImportPath string
	Standard   bool
	CFiles     []string
	CXXFiles   []string
	Imports    []string
	Module     *goModule
	Error      *struct{ Err string }
}

type goModule struct {
	Path, Version, Dir string
	Replace            *goModule
}

// C code that Go packages compile in. Every cgo package outside the standard library must be
// listed: what it bundles becomes a component, or nil when the C code is the module's own.
var cgoPackages = map[string]func(r *run, dir string) *Component{
	"golang.org/x/mobile/bind/java": nil,
	"github.com/mattn/go-sqlite3": func(r *run, dir string) *Component {
		b, err := os.ReadFile(filepath.Join(dir, "sqlite3-binding.h"))
		check(err)
		m := regexp.MustCompile(`#define SQLITE_VERSION\s+"([^"]+)"`).FindSubmatch(b)
		if m == nil {
			r.fail("go-sqlite3: no SQLITE_VERSION")
			return nil
		}
		v := string(m[1])
		return &Component{Type: "library", Name: "sqlite", Version: v, Licenses: licenses("blessing"),
			Purl:       "pkg:generic/sqlite@" + v,
			ExtRefs:    []ExtRef{{"vcs", "https://github.com/sqlite/sqlite"}},
			Properties: []Property{{propPrefix + "bundled-in", "github.com/mattn/go-sqlite3"}},
			section:    secC}
	},
}

// Licences the scanner cannot read off a module's licence files; each entry says why.
var goLicenseOverrides = map[string]string{}

func (r *run) golang() {
	// The same build settings as go/build.sh: Android, arm64 (the release ABI), cgo, its tags.
	// gomobile's generated main package adds bind/java and bind/seq.
	cmd := exec.Command("go", "list", "-deps", "-json", "-tags", "noupgrade",
		".", "golang.org/x/mobile/bind/java", "golang.org/x/mobile/bind/seq")
	cmd.Dir = r.path("go", "bridge")
	cmd.Env = append(os.Environ(), "GOOS=android", "GOARCH=arm64", "CGO_ENABLED=1", "GOFLAGS=-mod=readonly")
	cmd.Stderr = os.Stderr
	outb, err := cmd.Output()
	if err != nil {
		log.Fatalf("go list: %v", err)
	}
	var pkgs []goPackage
	dec := json.NewDecoder(bytes.NewReader(outb))
	for {
		var p goPackage
		if err := dec.Decode(&p); err == io.EOF {
			break
		} else {
			check(err)
		}
		if p.Error != nil {
			r.fail("go list %s: %s", p.ImportPath, p.Error.Err)
		}
		pkgs = append(pkgs, p)
	}

	sums := goSums(r.read("go", "bridge", "go.sum"))
	modOf := map[string]string{} // import path -> module path
	mods := map[string]*goModule{}
	for _, p := range pkgs {
		if !p.Standard && p.Module != nil {
			modOf[p.ImportPath] = p.Module.Path
			mods[p.Module.Path] = p.Module
		}
	}

	ref := map[string]string{}
	for mp, m := range mods {
		if mp == bridgeModule {
			continue // ours: part of the app
		}
		c := &Component{Type: "library", Name: mp, Version: m.Version, section: secGo}
		dir := m.Dir
		if m.Replace != nil {
			dir = m.Replace.Dir
		}
		if mp == stModule {
			// The submodule, through a replace: the version is pinned in a file instead.
			c.Version = strings.TrimSpace(r.read("go", "syncthing.version"))
			c.Description = "Syncthing core, from the go/syncthing submodule with go/patches applied"
			patches, _ := filepath.Glob(r.path("go", "patches", "*.patch"))
			for _, p := range patches {
				c.Properties = append(c.Properties, Property{propPrefix + "patch", "go/patches/" + filepath.Base(p)})
			}
			c.ExtRefs = []ExtRef{{"vcs", "https://github.com/syncthing/syncthing"}}
		} else if m.Replace != nil {
			r.fail("%s: unexpected replace", mp)
		} else if s := sums[mp+" "+m.Version]; s != "" {
			c.Properties = append(c.Properties, Property{propPrefix + "go.sum", s})
		} else {
			r.fail("%s %s: not in go/bridge/go.sum", mp, m.Version)
		}
		c.Purl = "pkg:golang/" + mp + "@" + c.Version
		if lic, ok := goLicenseOverrides[mp]; ok {
			c.Licenses = licenses(lic)
		} else {
			c.Licenses = licenses(r.scanLicenses(mp, dir))
		}
		ref[mp] = r.add(c).Ref
	}

	goVersion := strings.TrimPrefix(runtimeVersion(r), "go")
	std := r.add(&Component{Type: "library", Name: "stdlib", Version: goVersion,
		Description: "Go standard library and runtime", Licenses: licenses("BSD-3-Clause"),
		Purl: "pkg:golang/stdlib@" + goVersion, ExtRefs: []ExtRef{{"vcs", "https://github.com/golang/go"}}, section: secGo})
	r.app_.deps = append(r.app_.deps, std.Ref)

	// Module-level edges from package imports; the bridge and gomobile's glue hang off the app.
	edge := map[[2]string]bool{}
	for _, p := range pkgs {
		if p.Standard || p.Module == nil {
			continue
		}
		from := p.Module.Path
		for _, imp := range p.Imports {
			if to := modOf[imp]; to != "" && to != from {
				edge[[2]string{from, to}] = true
			}
		}
		if len(p.CFiles)+len(p.CXXFiles) > 0 {
			bundle, known := cgoPackages[p.ImportPath]
			switch {
			case !known:
				r.fail("%s compiles in C code: list it in cgoPackages", p.ImportPath)
			case bundle != nil:
				if c := bundle(r, p.Module.Dir); c != nil {
					r.add(c)
					r.comps[ref[from]].deps = append(r.comps[ref[from]].deps, c.Ref)
				}
			}
		}
	}
	edge[[2]string{bridgeModule, "golang.org/x/mobile"}] = true
	for e := range edge {
		if e[0] == bridgeModule {
			r.app_.deps = append(r.app_.deps, ref[e[1]])
		} else {
			r.comps[ref[e[0]]].deps = append(r.comps[ref[e[0]]].deps, ref[e[1]])
		}
	}
}

func goSums(s string) map[string]string {
	m := map[string]string{}
	for _, l := range strings.Split(s, "\n") {
		if f := strings.Fields(l); len(f) == 3 && !strings.HasSuffix(f[1], "/go.mod") {
			m[f[0]+" "+f[1]] = f[2]
		}
	}
	return m
}

// runtimeVersion is the Go release that builds the core: the module go/build.sh builds,
// go/bridge, names it (toolchain line, else go line), and CI's setup-go reads the same file.
func runtimeVersion(r *run) string {
	mod := r.read("go", "bridge", "go.mod")
	if m := regexp.MustCompile(`(?m)^toolchain (go\S+)$`).FindStringSubmatch(mod); m != nil {
		return m[1]
	}
	return "go" + r.find(`(?m)^go (\S+)$`, "go/bridge/go.mod")
}

var licenseFile = regexp.MustCompile(`(?i)^(licen[cs]e|copying)([.-].*)?$`)

// scanLicenses identifies the licences in the module's top-level licence files.
func (r *run) scanLicenses(mod, dir string) string {
	entries, err := os.ReadDir(dir)
	check(err)
	var ids []string
	for _, e := range entries {
		if e.IsDir() || !licenseFile.MatchString(e.Name()) {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		check(err)
		cov := licensecheck.Scan(b)
		if cov.Percent < 75 {
			r.fail("%s: %s is only %.0f%% a known licence: add it to goLicenseOverrides", mod, e.Name(), cov.Percent)
			continue
		}
		for _, m := range cov.Match {
			if !slices.Contains(ids, m.ID) {
				ids = append(ids, m.ID)
			}
		}
	}
	if len(ids) == 0 {
		r.fail("%s: no licence file found: add it to goLicenseOverrides", mod)
	}
	sort.Strings(ids)
	return strings.Join(ids, " AND ")
}

// Web GUI: the hand-kept list in webgui.json, checked against the files.

type webManifest struct {
	Comment    string `json:"comment"`
	Components []struct {
		Name      string   `json:"name"`
		Version   string   `json:"version"`
		Licenses  string   `json:"licenses"`
		Purl      string   `json:"purl"`
		URL       string   `json:"url"`
		Files     []string `json:"files"`
		SHA256    string   `json:"sha256"`
		BundledIn string   `json:"bundledIn"`
	} `json:"components"`
}

var thirdPartyGUI = regexp.MustCompile(`^[^/]+/(vendor|assets/font)/`)

func (r *run) webGUI() {
	var m webManifest
	check(json.Unmarshal([]byte(r.read("scripts/sbom/webgui.json")), &m))
	gui := r.path("go", "syncthing", "gui")
	var files []string
	check(filepath.WalkDir(gui, func(p string, d fs.DirEntry, err error) error {
		check(err)
		rel := filepath.ToSlash(strings.TrimPrefix(p, gui+string(filepath.Separator)))
		if !d.IsDir() && thirdPartyGUI.MatchString(rel) {
			files = append(files, rel)
		}
		return nil
	}))
	owner := map[string]int{}
	for _, f := range files {
		owner[f] = -1
		for i, c := range m.Components {
			for _, prefix := range c.Files {
				if strings.HasPrefix(f, prefix) {
					if owner[f] >= 0 {
						r.fail("webgui.json: %s is in both %q and %q", f, m.Components[owner[f]].Name, c.Name)
					}
					owner[f] = i
				}
			}
		}
		if owner[f] < 0 {
			r.fail("web GUI file gui/%s is in no entry of scripts/sbom/webgui.json", f)
		}
	}
	syncthing := r.comps["pkg:golang/"+stModule+"@"+strings.TrimSpace(r.read("go", "syncthing.version"))]
	byName := map[string]*Component{}
	for i, wc := range m.Components {
		c := &Component{Type: "library", Name: wc.Name, Version: wc.Version, Licenses: licenses(wc.Licenses),
			Purl: wc.Purl, Ref: "webgui:" + wc.Name, section: secWeb}
		if wc.URL != "" {
			c.ExtRefs = []ExtRef{{"vcs", wc.URL}}
		}
		if wc.BundledIn != "" {
			c.Properties = []Property{{propPrefix + "bundled-in", wc.BundledIn}}
		} else {
			h := sha256.New()
			n := 0
			for _, f := range files {
				if owner[f] == i {
					b, err := os.ReadFile(filepath.Join(gui, filepath.FromSlash(f)))
					check(err)
					fmt.Fprintf(h, "%s %x\n", f, sha256.Sum256(b))
					c.Properties = append(c.Properties, Property{propPrefix + "file", "go/syncthing/gui/" + f})
					n++
				}
			}
			sum := hex.EncodeToString(h.Sum(nil))
			if n == 0 {
				r.fail("webgui.json: %q matches no file", wc.Name)
			} else if sum != wc.SHA256 {
				r.fail("web GUI files of %q changed (Syncthing update?): check its version and licence in scripts/sbom/webgui.json, then set its sha256 to %s", wc.Name, sum)
			}
			// Not a CycloneDX hash: those are of one artifact, this is of a set of files.
			c.Properties = append(c.Properties, Property{propPrefix + "files-sha256", sum})
		}
		byName[wc.Name] = r.add(c)
	}
	for _, c := range m.Components {
		parent := syncthing
		if c.BundledIn != "" {
			parent = byName[c.BundledIn]
		}
		if parent == nil {
			r.fail("webgui.json: %q is bundled in unknown %q", c.Name, c.BundledIn)
			continue
		}
		parent.deps = append(parent.deps, byName[c.Name].Ref)
	}
}

// Build tools: what turns the sources into the APK, as pinned in the repository.

func (r *run) buildTools() {
	tool := func(name, version, purl, lic, where string) {
		r.add(&Component{Type: "application", Name: name, Version: version, Scope: "excluded",
			Purl: purl, Licenses: licenses(lic),
			Properties: []Property{{propPrefix + "pinned-in", where}}, section: secBuild})
	}
	goV := strings.TrimPrefix(runtimeVersion(r), "go")
	tool("go", goV, "pkg:golang/toolchain@"+goV, "BSD-3-Clause", "go/bridge/go.mod")
	xm := r.find(`(?m)^\s*golang.org/x/mobile (\S+)`, "go/bridge/go.mod")
	tool("gomobile", xm, "pkg:golang/golang.org/x/mobile/cmd/gomobile@"+xm, "BSD-3-Clause", "go/bridge/go.mod")
	ndk := r.find(`(?m)^mysyncthing\.ndkVersion=(\S+)$`, "gradle.properties")
	tool("android-ndk", ndk, "pkg:generic/android-ndk@"+ndk, "Apache-2.0", "gradle.properties")
	gw := r.find(`gradle-([0-9.]+)-bin\.zip`, "gradle/wrapper/gradle-wrapper.properties")
	tool("gradle", gw, "pkg:generic/gradle@"+gw, "Apache-2.0", "gradle/wrapper/gradle-wrapper.properties")
	agp := r.find(`(?m)^agp = "(.+)"$`, "gradle/libs.versions.toml")
	tool("android-gradle-plugin", agp, "pkg:maven/com.android.tools.build/gradle@"+agp, "Apache-2.0", "gradle/libs.versions.toml")
	kt := r.find(`(?m)^kotlin = "(.+)"$`, "gradle/libs.versions.toml")
	tool("kotlin", kt, "pkg:maven/org.jetbrains.kotlin/kotlin-compiler-embeddable@"+kt, "Apache-2.0", "gradle/libs.versions.toml")
	jdk := r.find(`jvmToolchain\((\d+)\)`, "app/build.gradle.kts")
	tool("jdk", jdk, "pkg:generic/openjdk@"+jdk, "GPL-2.0-only WITH Classpath-exception-2.0", "app/build.gradle.kts")
}

// Licences seen and accepted so far. The app is free software, and so must be everything in it:
// a new licence fails the run. Add one only when it is an SPDX id, the FSF lists it as a free
// software licence (https://www.gnu.org/licenses/license-list.html), and it is compatible with
// shipping inside an AGPL-3.0-or-later app (F-Droid takes the same licences).
var knownLicenses = map[string]bool{
	"AGPL-3.0-or-later": true, "Apache-2.0": true, "BSD-2-Clause": true, "BSD-2-Clause-Views": true,
	"BSD-3-Clause": true, "MIT": true, "MPL-2.0": true, "OFL-1.1": true,
	"blessing": true, // SQLite's public-domain dedication
	// Build tools only.
	"GPL-2.0-only": true, "Classpath-exception-2.0": true,
}

func (r *run) checkLicenses() {
	for _, c := range append([]*Component{r.app_}, slices.Collect(maps.Values(r.comps))...) {
		for _, l := range c.Licenses {
			ids := []string{l.Expression}
			if l.License != nil {
				ids = []string{l.License.ID}
			}
			for _, id := range strings.Fields(strings.NewReplacer("(", " ", ")", " ").Replace(ids[0])) {
				if id != "AND" && id != "OR" && id != "WITH" && !knownLicenses[id] {
					r.fail("%s: new licence %q: add it to knownLicenses only if it is a free software licence (see there)", displayName(c), id)
				}
			}
		}
	}
}

func licenses(expr string) []License {
	expr = strings.TrimSpace(expr)
	if expr == "" {
		return nil
	}
	if strings.ContainsAny(expr, " ()") {
		return []License{{Expression: expr}}
	}
	return []License{{License: &LicenseID{ID: expr}}}
}

func (r *run) bom() *BOM {
	b := &BOM{Format: "CycloneDX", Spec: "1.6", Version: 1}
	b.Metadata.Component = r.app_
	b.Metadata.Tools.Components = []*Component{{Type: "application", Ref: "tool:sbom", Name: "scripts/sbom.sh",
		Description: "MySyncthing's SBOM generator"}}
	all := []*Component{r.app_}
	for _, c := range r.comps {
		b.Components = append(b.Components, c)
		all = append(all, c)
	}
	sort.Slice(b.Components, func(i, j int) bool { return less(b.Components[i], b.Components[j]) })
	sort.Slice(all, func(i, j int) bool { return all[i].Ref < all[j].Ref })
	for _, c := range all {
		slices.Sort(c.deps)
		c.deps = slices.Compact(c.deps)
		if c.Scope != "excluded" {
			b.Dependencies = append(b.Dependencies, Dependency{Ref: c.Ref, DependsOn: c.deps})
		}
	}
	return b
}

func less(a, b *Component) bool {
	sa, sb := slices.Index(sections, a.section), slices.Index(sections, b.section)
	if sa != sb {
		return sa < sb
	}
	if na, nb := strings.ToLower(displayName(a)), strings.ToLower(displayName(b)); na != nb {
		return na < nb
	}
	return a.Ref < b.Ref
}

func displayName(c *Component) string {
	if c.Group != "" {
		return c.Group + ":" + c.Name
	}
	return c.Name
}

func markdown(b *BOM) []byte {
	var w bytes.Buffer
	app := b.Metadata.Component
	fmt.Fprintf(&w, "# Software bill of materials\n\n")
	fmt.Fprintf(&w, "Everything in the release APK of %s %s, with its licence. ", app.Name, app.Version)
	fmt.Fprintf(&w, "Machine-readable: [`sbom.cdx.json`](sbom.cdx.json) (CycloneDX %s), with checksums and the dependencies between components.\n\n", b.Spec)
	fmt.Fprintf(&w, "Generated by `scripts/sbom.sh`; do not edit. CI regenerates both files and fails when they differ, ")
	fmt.Fprintf(&w, "so they always match the dependencies. Each release on GitHub has the same file for its version attached.\n\n")
	counts := map[string]int{}
	for _, c := range b.Components {
		counts[c.section]++
	}
	fmt.Fprintf(&w, "| Part | Components |\n|---|---|\n")
	for _, s := range sections[1:] {
		fmt.Fprintf(&w, "| %s | %d |\n", s, counts[s])
	}
	fmt.Fprintf(&w, "\n## %s\n\n| Component | Version | Licence |\n|---|---|---|\n", secApp)
	fmt.Fprintf(&w, "| %s | %s | %s |\n", app.Name, app.Version, licenseText(app))
	for _, s := range sections[1:] {
		fmt.Fprintf(&w, "\n## %s\n\n", s)
		switch s {
		case secC:
			fmt.Fprintf(&w, "Compiled into the core by cgo.\n\n")
		case secWeb:
			fmt.Fprintf(&w, "Third-party files in Syncthing's web GUI, as listed in `scripts/sbom/webgui.json`.\n\n")
		case secBuild:
			fmt.Fprintf(&w, "Not in the APK; listed because they shape it. The build is reproducible with exactly these.\n\n"+
				"The Android NDK (listed with its source's licence) and the Android SDK platform the build compiles against are built from free source, Apache-2.0. "+
				"Google's prebuilt downloads of them, which the build uses (F-Droid's too), come under Google's Android SDK licence agreement, which the FSF does not count as free.\n\n")
		}
		fmt.Fprintf(&w, "| Component | Version | Licence |\n|---|---|---|\n")
		for _, c := range b.Components {
			if c.section != s {
				continue
			}
			v := c.Version
			if v == "" {
				v = "unversioned"
			}
			fmt.Fprintf(&w, "| %s | %s | %s |\n", displayName(c), v, licenseText(c))
		}
	}
	return w.Bytes()
}

func licenseText(c *Component) string {
	var s []string
	for _, l := range c.Licenses {
		if l.License != nil {
			s = append(s, l.License.ID)
		} else {
			s = append(s, l.Expression)
		}
	}
	return strings.Join(s, ", ")
}

// verifyBinary compares the Go modules in the SBOM with the ones the Go linker recorded.
func verifyBinary(sbomFile, so string) error {
	info, err := buildinfo.ReadFile(so)
	if err != nil {
		return fmt.Errorf("%s: %w", so, err)
	}
	b, err := os.ReadFile(sbomFile)
	if err != nil {
		return err
	}
	var bom BOM
	if err := json.Unmarshal(b, &bom); err != nil {
		return err
	}
	inBOM := map[string]bool{}
	for _, c := range bom.Components {
		if strings.HasPrefix(c.Purl, "pkg:golang/") && c.Scope != "excluded" && c.Name != "stdlib" {
			inBOM[c.Name+"@"+c.Version] = true
		}
		if c.Name == "stdlib" && "go"+c.Version != info.GoVersion {
			return fmt.Errorf("built with %s, SBOM says Go %s", info.GoVersion, c.Version)
		}
	}
	var errs []error
	inBinary := map[string]bool{}
	for _, d := range info.Deps {
		if d.Path == bridgeModule {
			continue // ours: part of the app
		}
		key := d.Path + "@" + d.Version
		if d.Path == stModule && d.Replace != nil {
			for k := range inBOM {
				if strings.HasPrefix(k, stModule+"@") {
					key = k
				}
			}
		}
		inBinary[key] = true
		if !inBOM[key] {
			errs = append(errs, fmt.Errorf("linked but not in the SBOM: %s", key))
		}
	}
	for k := range inBOM {
		if !inBinary[k] {
			errs = append(errs, fmt.Errorf("in the SBOM but not linked: %s", k))
		}
	}
	if errs == nil {
		fmt.Printf("%s: all %d Go modules match the SBOM\n", path.Base(so), len(inBinary))
	}
	return errors.Join(errs...)
}

func check(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
