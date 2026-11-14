//go:build race

package stbridge

// raceSlowdown: the race detector makes the GUI's password check (bcrypt) take
// seconds instead of about 50 ms, so waits for an answer are scaled with it.
const raceSlowdown = 10
