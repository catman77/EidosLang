from pathlib import Path
from hashlib import sha256
import sys

root = Path(sys.argv[1])
segment = root / "segment"
BLOCK = 16 * 1024
ZERO = b"\0" * 32

def bencode(x):
    if isinstance(x, int): return b"i" + str(x).encode() + b"e"
    if isinstance(x, bytes): return str(len(x)).encode() + b":" + x
    if isinstance(x, str): return bencode(x.encode())
    if isinstance(x, list): return b"l" + b"".join(map(bencode, x)) + b"e"
    if isinstance(x, dict):
        return b"d" + b"".join(bencode(k) + bencode(v) for k, v in sorted(x.items())) + b"e"
    raise TypeError(type(x))

def merkle_root(data):
    leaves = [sha256(data[i:i+BLOCK]).digest() for i in range(0, len(data), BLOCK)]
    n = 1
    while n < len(leaves): n <<= 1
    leaves += [ZERO] * (n - len(leaves))
    while len(leaves) > 1:
        leaves = [sha256(leaves[i] + leaves[i+1]).digest() for i in range(0, len(leaves), 2)]
    return leaves[0]

def insert(tree, path, props):
    cur = tree
    parts = path.split("/")
    for part in parts[:-1]: cur = cur.setdefault(part.encode(), {})
    cur[parts[-1].encode()] = {b"": props}

files = [(p.relative_to(segment).as_posix(), p.read_bytes()) for p in sorted(segment.rglob("*")) if p.is_file()]
file_tree, layers = {}, {}
for path, data in files:
    props = {b"length": len(data)}
    if data:
        root_hash = merkle_root(data)
        props[b"pieces root"] = root_hash
        if len(data) > BLOCK:
            layers[root_hash] = b"".join(sha256(data[i:i+BLOCK]).digest() for i in range(0, len(data), BLOCK))
    insert(file_tree, path, props)

ids = dict(line.split("=", 1) for line in (root / "ids.txt").read_text().splitlines())
name = f"eidolang-{ids['conversation_id'][:12]}-{ids['segment_id'][:12]}"
info = {b"file tree": file_tree, b"meta version": 2, b"name": name.encode(), b"piece length": BLOCK}
meta = {b"info": info}
if layers: meta[b"piece layers"] = layers
info_hash = sha256(bencode(info)).hexdigest()
assert info_hash == ids["torrent_infohash_v2"]
assert bencode(meta) == (root / "segment.torrent").read_bytes()
print("PASS independent Python BEP52 infohash")
print("PASS independent Python BEP52 metainfo byte equality")
print("INFOHASH=" + info_hash)
