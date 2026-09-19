#!/usr/bin/env python3
"""Protocol V2 cross-language reference model. No physical-board claims."""
import hashlib
import hmac
import struct
import unittest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SECRET = bytes(range(32))
APP_NONCE = bytes(range(16,32))
ESP_NONCE = bytes(range(32,48))

def mac(key, data):
    return hmac.new(key, data, hashlib.sha256).digest()

def session(node):
    return mac(SECRET, b"SESSION" + bytes([node]) + APP_NONCE + ESP_NONCE)

def frame(node, seq, score):
    key = session(node)
    plain = struct.pack("<BBHIIIIBBBBHH",2,node,1,123,100000,90000,4000000,
                        3,score,211,2,10,20)
    assert len(plain)==28
    seq_bytes = struct.pack("<I",seq)
    aad = b"\x10" + seq_bytes
    iv = mac(key,b"IV")[:8] + seq_bytes
    return aad + AESGCM(key).encrypt(iv,plain,aad)

class ProtocolV2Tests(unittest.TestCase):
    def test_hmac_known_vectors(self):
        self.assertEqual(mac(SECRET,b"ESP-AUTH"+bytes([1])+APP_NONCE+ESP_NONCE).hex(),
            "b7d4f78fb75ff94328e5bec23497714cd57991c0b2adbb43bc238551624130e6")
        self.assertEqual(session(1).hex(),
            "7aa7b553f72eee93e11894ac2df293b0487384fc62893de2263a4457220f6cf6")
    def test_three_node_key_separation(self):
        self.assertEqual(len({session(n) for n in (1,2,3)}),3)
    def test_gcm_tag_corruption_rejected(self):
        data = frame(2,7,66)
        self.assertEqual(len(data),49)
        seq = data[1:5]; key = session(2)
        iv = mac(key,b"IV")[:8]+seq
        plaintext = AESGCM(key).decrypt(iv,data[5:],data[:5])
        self.assertEqual((plaintext[0],plaintext[1]),(2,2))
        corrupted=bytearray(data); corrupted[15]^=1
        with self.assertRaises(Exception):
            AESGCM(key).decrypt(iv,bytes(corrupted[5:]),bytes(corrupted[:5]))
    def test_strict_monotonic_sequence_rule(self):
        last=-1; accepted=[]
        for seq in [0,1,1,0,3,2,4]:
            if seq > last:
                last=seq; accepted.append(seq)
        self.assertEqual(accepted,[0,1,3,4])

if __name__ == "__main__":
    unittest.main(verbosity=2)
