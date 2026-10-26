// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"strings"

	"github.com/syncthing/syncthing/lib/fs"
	"github.com/syncthing/syncthing/lib/ignore"
)

// CheckIgnores returns why the core would fail on these ignore patterns
// (one per line), or "" when they are fine. The core saves any patterns it is
// given and only then finds them invalid, which stops the folder; checking
// first keeps a typo such as a lone "!" from breaking it. #include files are
// looked up in folderPath; for the defaults (folderPath "") there are none,
// as in a new folder.
func CheckIgnores(folderPath, patterns string) string {
	filesystem := fs.NewFilesystem(fs.FilesystemTypeFake, "checkignores?files=0")
	if folderPath != "" {
		filesystem = fs.NewFilesystem(fs.FilesystemTypeBasic, folderPath)
	}
	if err := ignore.New(filesystem).Parse(strings.NewReader(patterns), ".stignore"); err != nil {
		return err.Error()
	}
	return ""
}
