package main

import "errors"

// Secure fica ligado por padrão; desligar só é aceito com APP_ENV=development
// explícito, para um .env de desenvolvimento esquecido em produção derrubar a
// subida em vez de enviar o cookie de sessão por HTTP. APP_ENV ausente conta
// como produção.
func leCookieSecure(valor, appEnv string) (bool, error) {
	switch valor {
	case "", "true":
		return true, nil
	case "false":
		if appEnv != "development" {
			return false, errors.New("COOKIE_SECURE=false só é aceito com APP_ENV=development")
		}
		return false, nil
	default:
		return false, errors.New("COOKIE_SECURE deve ser true ou false")
	}
}
