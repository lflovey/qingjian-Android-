#!/usr/bin/env python3
import sys, tarfile, os

def main():
    if len(sys.argv) != 3:
        print('usage: extract_model.py <tar_path> <out_dir>', file=sys.stderr)
        return 1
    tar_path, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    target = 'model.int8.onnx'
    arch = tarfile.open(tar_path, 'r:bz2')
    found = False
    for m in arch.getmembers():
        if m.name.endswith('/' + target):
            src = arch.extractfile(m)
            dst = os.path.join(out_dir, target)
            with src, open(dst, 'wb') as f:
                f.write(src.read())
            print('extracted: %s (%d bytes)' % (dst, os.path.getsize(dst)))
            found = True
            break
    arch.close()
    if not found:
        print('model.int8.onnx NOT FOUND in archive', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
