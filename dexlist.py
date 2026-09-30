#!/usr/bin/env python3
"""List declared methods of classes matching a substring, from dex files."""
import struct, sys, glob

def uleb(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7f) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off

class Dex:
    def __init__(self, path):
        self.d = open(path, 'rb').read()
        d = self.d
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from('<12I', d, 56)
        self._str_cache = {}

    def string(self, idx):
        if idx in self._str_cache:
            return self._str_cache[idx]
        off = struct.unpack_from('<I', self.d, self.string_ids_off + idx * 4)[0]
        p = off
        _, p = uleb(self.d, p)          # utf16 length
        end = self.d.index(b'\x00', p)
        s = self.d[p:end].decode('utf-8', 'replace')
        self._str_cache[idx] = s
        return s

    def type_desc(self, idx):
        return self.string(struct.unpack_from('<I', self.d,
                          self.type_ids_off + idx * 4)[0])

    def method(self, idx):
        class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', self.d,
                                        self.method_ids_off + idx * 8)
        return (self.type_desc(class_idx), self.string(name_idx), proto_idx)

    def proto_shorty(self, proto_idx):
        shorty_idx = struct.unpack_from('<I', self.d,
                        self.proto_ids_off + proto_idx * 12)[0]
        return self.string(shorty_idx)

    def classes(self):
        for i in range(self.class_defs_size):
            off = self.class_defs_off + i * 32
            class_idx = struct.unpack_from('<I', self.d, off)[0]
            class_data_off = struct.unpack_from('<I', self.d, off + 24)[0]
            yield self.type_desc(class_idx), class_data_off

    def methods_of(self, class_data_off):
        """Return list of (method_idx, access_flags) declared by the class."""
        out = []
        if class_data_off == 0:
            return out
        p = class_data_off
        sf, p = uleb(self.d, p)
        inf, p = uleb(self.d, p)
        dm, p = uleb(self.d, p)
        vm, p = uleb(self.d, p)
        for _ in range(sf):
            _, p = uleb(self.d, p); _, p = uleb(self.d, p)
        for _ in range(inf):
            _, p = uleb(self.d, p); _, p = uleb(self.d, p)
        for count in (dm, vm):
            idx = 0
            for _ in range(count):
                diff, p = uleb(self.d, p)
                flags, p = uleb(self.d, p)
                code, p = uleb(self.d, p)
                idx += diff
                out.append((idx, flags, code))
        return out


def main():
    needle = sys.argv[1]
    paths = sys.argv[2:] or sorted(glob.glob('/root/work/lsp/sysui/classes*.dex'))
    for path in paths:
        dx = Dex(path)
        for desc, cdo in dx.classes():
            if needle not in desc:
                continue
            print('=' * 8, desc, '  [' + path.split('/')[-1] + ']')
            for idx, flags, code in dx.methods_of(cdo):
                cls, name, proto = dx.method(idx)
                shorty = dx.proto_shorty(proto)
                print('   %-6s %-30s %s  code=%s' % (
                    hex(flags), name, shorty, 'yes' if code else 'NO'))

if __name__ == '__main__':
    main()
