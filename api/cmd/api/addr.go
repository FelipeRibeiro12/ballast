package main

import "net"

// Padrão é loopback: ":porta" escuta em todas as interfaces e expõe a API
// para quem está na mesma rede. Container/produção define HOST=0.0.0.0.
const defaultHost = "127.0.0.1"

func listenAddr(host, port string) string {
	if host == "" {
		host = defaultHost
	}
	return net.JoinHostPort(host, port)
}
