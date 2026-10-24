package com.felipe.ballast.identity;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

// Porta de ação sensível (ADR-053): com o MFA ligado, a ação pede o código do app ou um de
// recuperação; sem MFA, passa. Quem chama não conhece o MFA, só pede o código.
@Service
public class MfaGate {

  private final MfaService mfa;

  MfaGate(MfaService mfa) {
    this.mfa = mfa;
  }

  // Lança MfaError (400 código inválido, 429 no atraso) quando o código falta ou não vale. O
  // código errado conta no atraso por usuário, como no login.
  public void requireCode(UUID userId, String code) {
    if (!mfa.isActive(userId)) {
      return;
    }
    if (code == null || !mfa.verify(userId, code)) {
      throw MfaError.of(HttpStatus.BAD_REQUEST, "código inválido");
    }
  }
}
