package core

import "tailscale.com/net/netmon"

func updateDefaultGateway(ip string) { netmon.UpdateLastKnownDefaultGateway(ip) }
