#!/usr/bin/env python3
import datetime as dt
import importlib.util
import os
from pathlib import Path
import stat
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("prepare-ci-signing.py")
spec = importlib.util.spec_from_file_location("prepare_ci_signing", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


class PrepareCiSigningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.runner_temp = root / "runner-temp"
        self.github_env = root / "github-env"
        self.now = dt.datetime(2026, 1, 2, tzinfo=dt.timezone.utc)

    def tearDown(self):
        self.temp.cleanup()

    def prepare(self, environment, require=False):
        return module.prepare(environment, self.runner_temp, self.github_env, require, self.now)

    def test_missing_signing_allows_explicit_ephemeral_debug_build(self):
        message = self.prepare({})
        self.assertIn("cannot update an existing Friday install", message)
        expected = int((self.now - module.EPOCH).total_seconds())
        self.assertEqual(self.github_env.read_text(), f"FRIDAY_VERSION_CODE={expected}\n")
        self.assertFalse(self.runner_temp.exists())

    def test_missing_signing_fails_when_distribution_requires_it(self):
        with self.assertRaisesRegex(module.PreparationError, "Signing is required"):
            self.prepare({}, require=True)

    def test_partial_signing_fails_and_does_not_write_keystore(self):
        environment = {"FRIDAY_SIGNING_KEYSTORE_BASE64": "a2V5"}
        with self.assertRaisesRegex(module.PreparationError, "Partial Friday signing configuration"):
            self.prepare(environment)
        self.assertFalse((self.runner_temp / "friday-ci-signing.keystore").exists())

    def test_invalid_base64_fails_strictly(self):
        environment = {
            "FRIDAY_SIGNING_KEYSTORE_BASE64": "not base64!",
            "FRIDAY_SIGNING_STORE_PASSWORD": "store-password",
            "FRIDAY_SIGNING_KEY_ALIAS": "alias",
            "FRIDAY_SIGNING_KEY_PASSWORD": "key-password",
        }
        with self.assertRaisesRegex(module.PreparationError, "strict valid Base64"):
            self.prepare(environment)

    def test_configured_signing_writes_private_file_and_only_nonsecret_env(self):
        environment = {
            "FRIDAY_SIGNING_KEYSTORE_BASE64": "a2V5LWJ5dGVz",
            "FRIDAY_SIGNING_STORE_PASSWORD": "store-password",
            "FRIDAY_SIGNING_KEY_ALIAS": "alias",
            "FRIDAY_SIGNING_KEY_PASSWORD": "key-password",
            "FRIDAY_VERSION_CODE": "24",
        }
        self.assertIn("prepared", self.prepare(environment))
        store = self.runner_temp / "friday-ci-signing.keystore"
        self.assertEqual(store.read_bytes(), b"key-bytes")
        self.assertEqual(stat.S_IMODE(store.stat().st_mode), 0o600)
        env_text = self.github_env.read_text()
        self.assertEqual(env_text, f"FRIDAY_SIGNING_STORE_FILE={store}\nFRIDAY_VERSION_CODE=24\n")
        self.assertNotIn("password", env_text)
        self.assertNotIn("alias", env_text)

    def test_version_code_bounds(self):
        for value in ("0", "-1", "2100000001", "1.5", "abc"):
            with self.subTest(value=value):
                with self.assertRaisesRegex(module.PreparationError, "FRIDAY_VERSION_CODE"):
                    self.prepare({"FRIDAY_VERSION_CODE": value})
        self.assertIn("ephemeral debug key", self.prepare({"FRIDAY_VERSION_CODE": "2100000000"}))


if __name__ == "__main__":
    unittest.main()
