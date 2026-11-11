// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"fmt"
	"strings"

	"github.com/syncthing/syncthing/lib/fs"
	"github.com/syncthing/syncthing/lib/ignore"
)

// CheckIgnores returns why the core would fail on these ignore patterns
// (one per line), or "" when they are fine. The core saves any patterns it is
// given and only then finds them invalid, which stops the folder; checking
// first keeps a typo such as a lone "!" from breaking it. #include files are
// looked up in folderPath; for the defaults (folderPath "") there are none,
// as in a new folder. Patterns over maxIgnores bytes are refused: the app's
// editor takes no more.
func CheckIgnores(folderPath, patterns string) string {
	if len(patterns) > maxIgnores {
		return fmt.Sprintf("more than %d bytes of ignore patterns", maxIgnores)
	}
	filesystem := fs.NewFilesystem(fs.FilesystemTypeFake, "checkignores?files=0")
	if folderPath != "" {
		filesystem = fs.NewFilesystem(fs.FilesystemTypeBasic, folderPath)
	}
	if err := ignore.New(filesystem).Parse(strings.NewReader(patterns), ".stignore"); err != nil {
		return err.Error()
	}
	return ""
}
