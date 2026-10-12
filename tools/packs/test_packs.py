#!/usr/bin/env python3
"""
Tests of the pack tools (no network): the container, signatures, the feature check and the Lottie
importer. Run: python3 -I tools/packs/test_packs.py
"""
import json
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import artcheck  # noqa: E402
import import_lottie  # noqa: E402
import packlib  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402

KEY = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))


def lottie(layers, **extra):
    doc = {"v": "5.7.0", "fr": 30, "ip": 0, "op": 60, "w": 200, "h": 200, "nm": "Rocket Comp", "ddd": 0, "assets": [], "layers": layers}
    doc.update(extra)
    return doc


def shape_layer(name, shapes=None, **extra):
    layer = {"ddd": 0, "ty": 4, "nm": name, "ks": {"o": {"a": 0, "k": 100}}, "ip": 0, "op": 60, "st": 0,
             "shapes": shapes if shapes is not None else [{"ty": "el", "p": {"a": 0, "k": [0, 0]}, "s": {"a": 0, "k": [50, 50]}},
                                                          {"ty": "fl", "c": {"a": 0, "k": [1, 0, 0, 1]}, "o": {"a": 0, "k": 100}}]}
    layer.update(extra)
    return layer


class PackTools(unittest.TestCase):
    def test_container_round_trip_and_safe_paths(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.tpk")
            size, sha = packlib.write_tpk([("a/b.json", b"{}"), ("c.wav", b"\x00\x01")], p)
            self.assertEqual(size, os.path.getsize(p))
            self.assertEqual(dict(packlib.read_tpk(p)), {"a/b.json": b"{}", "c.wav": b"\x00\x01"})
            with self.assertRaises(ValueError):
                packlib.write_tpk([("../evil", b"")], p)

    def test_manifest_signature_covers_the_signed_fields(self):
        m = packlib.manifest("art.x", "Art", 2, 10, "AB" * 32, "art.x-v2.tpk", "MIT", KEY, titleEn="X")
        pub = KEY.public_key()
        pub.verify(bytes.fromhex(m["signature"]), packlib.signed_bytes(m))
        tampered = dict(m, license="CC-BY-SA-4.0")
        with self.assertRaises(Exception):
            pub.verify(bytes.fromhex(m["signature"]), packlib.signed_bytes(tampered))
        self.assertEqual(packlib.signed_bytes(m).decode().split("\n")[0], "trimio-pack/1")

    def test_feature_check_mirrors_the_reader(self):
        self.assertIsNone(artcheck.lottie_problem(lottie([shape_layer("Rocket")])))
        self.assertEqual(artcheck.lottie_problem(lottie([shape_layer("T", ty=5)])), "text layer")
        self.assertEqual(artcheck.lottie_problem(lottie([shape_layer("I", ty=2)])), "image layer")
        self.assertEqual(artcheck.lottie_problem(lottie([shape_layer("E", ef=[{"ty": 5}])])), "layer effects")
        self.assertEqual(artcheck.lottie_problem(lottie([shape_layer("R", shapes=[{"ty": "rp"}])])), "shape item rp")
        expr = shape_layer("X")
        expr["ks"]["p"] = {"a": 0, "k": [0, 0], "x": "wiggle(2,10)"}
        self.assertEqual(artcheck.lottie_problem(lottie([expr])), "expression")
        self.assertEqual(artcheck.lottie_problem({"nope": 1}), "not a Lottie animation")
        self.assertIsNone(artcheck.svg_problem('<path d="M0 0h4"><animate attributeName="d" dur="1s" values="M0 0h4;M0 0h8"/></path>'))
        self.assertEqual(artcheck.svg_problem('<use href="#a"/>'), "svg <use>")
        self.assertEqual(artcheck.svg_problem('<path d="M0 0"><animate attributeName="d" begin="a.end" dur="1s" values="x"/></path>'), "svg timing a.end")

    def test_importer_validates_tags_and_packs(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in")
            os.makedirs(os.path.join(src, "sub"))
            with open(os.path.join(src, "Rocket_Launch-02.json"), "w") as f:
                json.dump(lottie([shape_layer("Flame"), shape_layer("Flame 2"), shape_layer("Shape Layer 1")]), f)
            with open(os.path.join(src, "sub", "price-tag.json"), "w") as f:
                json.dump(lottie([shape_layer("Text", ty=5)]), f)
            with zipfile.ZipFile(os.path.join(src, "money.lottie"), "w") as z:
                z.writestr("manifest.json", "{}")
                z.writestr("animations/a1.json", json.dumps(lottie([shape_layer("Coin")])))
            with open(os.path.join(src, "broken.json"), "w") as f:
                f.write("{not json")
            tags = os.path.join(d, "tags.csv")
            with open(tags, "w", encoding="utf-8") as f:
                f.write("file,tags,tags_fa\nRocket_Launch-02.json,startup;growth,موشک;رشد\n")
            out = os.path.join(d, "out")
            m, refused = import_lottie.build(src, out, "art.team", 3, tags, "تیم", "Team", "Lottie-Simple-License", KEY)
            self.assertEqual(m["items"], 2)
            self.assertEqual(set(refused), {"price-tag.json", "broken.json"})
            self.assertEqual(refused["price-tag.json"], "text layer")
            files = dict(packlib.read_tpk(os.path.join(out, "art.team-v3.tpk")))
            index = json.loads(files["art/index.json"])
            rows = {r[1]: r for r in index["art"]}
            self.assertEqual(set(rows), {"rocket-launch", "money"})
            self.assertIn("startup", rows["rocket-launch"][3])
            self.assertIn("flame", rows["rocket-launch"][3])
            self.assertEqual(rows["rocket-launch"][4], ["موشک", "رشد"])
            self.assertIn("coin", rows["money"][3])
            self.assertIn("art/docs/money.json", files)
            self.assertEqual(index["sources"]["team"]["docs"], "art/docs/{id}.json")
            KEY.public_key().verify(bytes.fromhex(m["signature"]), packlib.signed_bytes(m))
            with self.assertRaises(SystemExit):
                import_lottie.build(src, out, "art.team", 4, None, "", "", "CC-BY-NC-4.0", KEY)


if __name__ == "__main__":
    unittest.main()
