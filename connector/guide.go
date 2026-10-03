package main

import (
	_ "embed"
	"fmt"
)

// The building guide's one source is docs/building-apps.md. Go can't embed a file from
// outside the module, so building-apps.md here is a copy, which TestGuideMatchesDocs
// keeps equal.
//
//go:generate cp ../docs/building-apps.md building-apps.md

//go:embed building-apps.md
var buildingGuide string

// cmdGuide prints the building guide for coding agents, as this version of the connector
// knows it.
func cmdGuide(args []string) error {
	fs, _ := newFlags("guide")
	if _, err := parseArgs(fs, args, 0); err != nil {
		return err
	}
	fmt.Print(buildingGuide)
	return nil
}
