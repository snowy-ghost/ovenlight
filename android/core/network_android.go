package core

import "tailscale.com/net/netmon"

// updateDefaultRoute tells netmon which interface and gateway the phone's default network
// uses, which Android's netmon can't learn for itself.
func updateDefaultRoute(ifName, gatewayIP string) {
	netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	netmon.UpdateLastKnownDefaultGateway(gatewayIP)
}
