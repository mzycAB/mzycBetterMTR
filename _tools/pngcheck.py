"""Decode MTR escalator PNG strips and test whether frames are vertical rolls of frame 0."""
import struct
import sys
import zlib


def decode(path):
    d = open(path, 'rb').read()
    pos = 8
    idat = b''
    w = h = bd = ct = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        if typ == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', data[:10])
        elif typ == b'IDAT':
            idat += data
        pos += 12 + ln
    raw = zlib.decompress(idat)
    bpp = {0: 1, 2: 3, 4: 2, 6: 4}[ct] * (bd // 8)
    stride = 1 + w * bpp
    out = bytearray(w * bpp * h)
    prev = bytearray(w * bpp)
    for y in range(h):
        ft = raw[y * stride]
        line = raw[y * stride + 1:(y + 1) * stride]
        cur = bytearray(line)
        if ft == 1:
            for i in range(bpp, len(cur)):
                cur[i] = (cur[i] + cur[i - bpp]) & 0xFF
        elif ft == 2:
            for i in range(len(cur)):
                cur[i] = (cur[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(len(cur)):
                a = cur[i - bpp] if i >= bpp else 0
                cur[i] = (cur[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(len(cur)):
                a = cur[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 0xFF
        out[y * w * bpp:(y + 1) * w * bpp] = cur
        prev = cur
    return w, h, bpp, bytes(out)


def frame_rows(w, bpp, data, fh, fi):
    a = fi * fh * w * bpp
    return data[a:a + fh * w * bpp]


def main(path):
    w, h, bpp, data = decode(path)
    fh = w  # square frames
    n = h // fh
    print(f'{path}: {w}x{h} bpp={bpp} frames={n} frameHeight={fh}')
    f0 = frame_rows(w, bpp, data, fh, 0)
    # does frame k == frame 0 rolled up by k*fh/n rows?
    step = fh // n
    print(f'  roll step = {step} px/frame')
    for k in range(n):
        fk = frame_rows(w, bpp, data, fh, k)
        best = None
        for shift in range(fh):
            # frame0 shifted up by `shift` (i.e. fk[i] == f0[i+shift], wrap)
            ok = True
            for probe in range(0, fh, 8):
                src = (probe + shift) % fh
                if fk[probe * w * bpp:(probe + 1) * w * bpp] != f0[src * w * bpp:(src + 1) * w * bpp]:
                    ok = False
                    break
            if ok:
                best = shift
                break
        print(f'  frame{k}: identical-to-frame0={fk == f0}  matched-shift-up={best}')


if __name__ == '__main__':
    for p in sys.argv[1:]:
        main(p)
