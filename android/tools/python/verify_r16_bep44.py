from pathlib import Path
import sys, json, base64, hashlib
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
root = Path(sys.argv[1])

def dec(b, i=0):
    c=b[i:i+1]
    if c==b'i':
        j=b.index(b'e',i); return int(b[i+1:j]),j+1
    if c==b'l':
        out=[]; i+=1
        while b[i:i+1]!=b'e': v,i=dec(b,i); out.append(v)
        return out,i+1
    if c==b'd':
        out={}; i+=1
        while b[i:i+1]!=b'e': k,i=dec(b,i); v,i=dec(b,i); out[k]=v
        return out,i+1
    j=b.index(b':',i); n=int(b[i:j]); j+=1; return b[j:j+n],j+n

def enc(x):
    if isinstance(x,int): return b'i'+str(x).encode()+b'e'
    if isinstance(x,bytes): return str(len(x)).encode()+b':'+x
    if isinstance(x,list): return b'l'+b''.join(enc(v) for v in x)+b'e'
    if isinstance(x,dict): return b'd'+b''.join(enc(k)+enc(v) for k,v in sorted(x.items()))+b'e'
    raise TypeError(type(x))

put, end = dec((root / "head-put.bencode").read_bytes())
assert end == len((root / "head-put.bencode").read_bytes())
cert = json.loads((root / "publisher-certificate.json").read_text())
raw = base64.urlsafe_b64decode(cert["body"]["dht_public_key_raw_b64"] + "==")
signable = enc(b"salt") + enc(put[b"salt"]) + enc(b"seq") + enc(put[b"seq"]) + enc(b"v") + enc(put[b"v"])
Ed25519PublicKey.from_public_bytes(raw).verify(put[b"sig"], signable)
target = hashlib.sha1(raw + put[b"salt"]).hexdigest()
ids = dict(line.split("=",1) for line in (root / "ids.txt").read_text().splitlines())
assert target == ids["bep44_target"]
assert len(enc(put[b"v"])) <= 1000
print("PASS independent Python BEP44 Ed25519 signature")
print("PASS independent Python BEP44 target SHA1(k||salt)")
print("PASS independent Python BEP44 value <=1000 bytes")
print("TARGET=" + target)
