package com.felipe.ballast.crypto;

// Identidade do ativo para o provedor: rede mais contrato, nulo no nativo. Nunca o símbolo.
public record AssetRef(CryptoNetwork network, String contract) {}
