package main

import "errors"

// pendingSwap is an update whose moment has come: updates are on, the release is
// extracted, and either its quiet moment came and a fresh fetch of the published index
// still names it, or update now --index asked for it.
type pendingSwap struct {
	Version string // the release it installs, newer than the running one
	// Next is bin/ovenlight.next (ovenlight.next.exe on Windows), taken from the archive
	// the index lists. Nothing has run it yet.
	Next string
	// Index is the signed index that named the release, as fetched or as update now was
	// given it, and Sig its signature in Namespace: nsLatest, or nsCanary for update now
	// --index. The trial checks it with the new binary's own keys.
	Index, Sig []byte
	Namespace  string
}

// swapIn checks that Next runs and is p.Version, records the trial, puts Next in place
// of the running binary and has the connector exit through its normal shutdown, so the
// service starts the new one. It returns nil once the exit has begun, so a control
// request can still be answered. An error means nothing was swapped and the running
// binary is in place: the updater records it as the version's failure and tries it again
// the next night.
func (d *daemon) swapIn(p pendingSwap) error {
	return errors.New("swap not implemented")
}
