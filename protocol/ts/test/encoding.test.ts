import { describe, expect, it } from 'vitest';
import { b64decode, b64decodeLength, b64encode, B64Error } from '../src/b64.ts';
import { concat, equalBytes, fromHex, fromUtf8, isAllZero, lp, toHex, u32be, u64, utf8 } from '../src/bytes.ts';

describe('b64', () => {
  it('round-trips every length 0..64 and matches Buffer base64url', () => {
    for (let n = 0; n <= 64; n++) {
      const data = Uint8Array.from({ length: n }, (_, i) => (i * 37 + n) & 0xff);
      const s = b64encode(data);
      expect(s).toBe(Buffer.from(data).toString('base64url'));
      expect(s.length).toBe(Math.ceil((n * 4) / 3));
      expect(b64decode(s)).toEqual(data);
    }
  });

  it.each(['=', 'AA==', 'A', 'AB', 'AAB', 'A/A=', 'AA AA', 'AA\tA', 'Ä'])('rejects %j', (s) => {
    expect(() => b64decode(s)).toThrow(B64Error);
  });

  it('b64decodeLength checks type and length without throwing', () => {
    expect(b64decodeLength(b64encode(new Uint8Array(32)), 32)).toEqual(new Uint8Array(32));
    expect(b64decodeLength(b64encode(new Uint8Array(31)), 32)).toBeNull();
    expect(b64decodeLength('A', 0)).toBeNull();
    expect(b64decodeLength(42, 0)).toBeNull();
  });
});

describe('bytes', () => {
  it('lp encodes strings as UTF-8 with a u32be length', () => {
    expect(toHex(lp('ł'))).toBe('00000002c582');
    expect(toHex(lp(new Uint8Array(0)))).toBe('00000000');
  });

  it('u64 and u32be enforce their ranges', () => {
    expect(toHex(u64(2n ** 64n - 1n))).toBe('ffffffffffffffff');
    expect(() => u64(-1)).toThrow(RangeError);
    expect(() => u64(1.5)).toThrow(RangeError);
    expect(() => u64(2 ** 53)).toThrow(RangeError);
    expect(() => u64(2n ** 64n)).toThrow(RangeError);
    expect(() => u64(-1n)).toThrow(RangeError);
    expect(() => u32be(2 ** 32)).toThrow(RangeError);
    expect(() => u32be(-1)).toThrow(RangeError);
  });

  it('equalBytes, isAllZero, concat', () => {
    expect(equalBytes(fromHex('0102'), fromHex('0102'))).toBe(true);
    expect(equalBytes(fromHex('0102'), fromHex('0103'))).toBe(false);
    expect(equalBytes(fromHex('01'), fromHex('0102'))).toBe(false);
    expect(isAllZero(new Uint8Array(4))).toBe(true);
    expect(isAllZero(fromHex('0001'))).toBe(false);
    expect(toHex(concat(fromHex('01'), new Uint8Array(0), fromHex('0203')))).toBe('010203');
  });

  it('hex and UTF-8 decoding are strict', () => {
    expect(() => fromHex('0')).toThrow();
    expect(() => fromHex('0G')).toThrow();
    expect(() => fromHex('AB')).toThrow();
    expect(() => fromUtf8(fromHex('ff'))).toThrow();
    expect(fromUtf8(utf8('Michał 🎉'))).toBe('Michał 🎉');
  });
});
