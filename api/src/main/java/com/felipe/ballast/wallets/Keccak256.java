package com.felipe.ballast.wallets;

// Keccak-256 original (padding 0x01), o do Ethereum, não o SHA3-256 do NIST. Só o EIP-55 precisa
// dele; escrito à mão para não trazer uma biblioteca inteira (ADR-071).
final class Keccak256 {

  private static final int RATE_BYTES = 136;

  private static final long[] ROUND_CONSTANTS = {
    0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
    0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
    0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
    0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
    0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
    0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
  };

  private static final int[] ROTATIONS = {
    1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
  };

  private static final int[] LANES = {
    10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
  };

  private Keccak256() {}

  static byte[] hash(byte[] input) {
    long[] state = new long[25];
    int offset = 0;
    while (input.length - offset >= RATE_BYTES) {
      absorb(state, input, offset, RATE_BYTES);
      permute(state);
      offset += RATE_BYTES;
    }
    byte[] last = new byte[RATE_BYTES];
    System.arraycopy(input, offset, last, 0, input.length - offset);
    last[input.length - offset] ^= 0x01;
    last[RATE_BYTES - 1] ^= (byte) 0x80;
    absorb(state, last, 0, RATE_BYTES);
    permute(state);
    byte[] digest = new byte[32];
    for (int i = 0; i < 32; i++) {
      digest[i] = (byte) (state[i / 8] >>> (8 * (i % 8)));
    }
    return digest;
  }

  private static void absorb(long[] state, byte[] block, int offset, int length) {
    for (int i = 0; i < length; i++) {
      state[i / 8] ^= (block[offset + i] & 0xffL) << (8 * (i % 8));
    }
  }

  private static void permute(long[] state) {
    long[] column = new long[5];
    for (int round = 0; round < 24; round++) {
      for (int x = 0; x < 5; x++) {
        column[x] = state[x] ^ state[x + 5] ^ state[x + 10] ^ state[x + 15] ^ state[x + 20];
      }
      for (int x = 0; x < 5; x++) {
        long mix = column[(x + 4) % 5] ^ Long.rotateLeft(column[(x + 1) % 5], 1);
        for (int y = 0; y < 25; y += 5) {
          state[y + x] ^= mix;
        }
      }
      long carried = state[1];
      for (int i = 0; i < 24; i++) {
        int lane = LANES[i];
        long next = state[lane];
        state[lane] = Long.rotateLeft(carried, ROTATIONS[i]);
        carried = next;
      }
      for (int y = 0; y < 25; y += 5) {
        for (int x = 0; x < 5; x++) {
          column[x] = state[y + x];
        }
        for (int x = 0; x < 5; x++) {
          state[y + x] ^= ~column[(x + 1) % 5] & column[(x + 2) % 5];
        }
      }
      state[0] ^= ROUND_CONSTANTS[round];
    }
  }
}
