#!/usr/bin/env python3
"""Prepare Friday's CI signing key without writing credentials to the checkout."""

from __future__ import annotations

import argparse
import base64
import binascii
import datetime as dt
import os
from pathlib import Path
import stat
import sys
from typing import Mapping

EPOCH = dt.datetime(2020, 1, 1, tzinfo=dt.timezone.utc)
MAX_VERSION_CODE = 2_100_000_000
SIGNING_ENV = (
    "FRIDAY_SIGNING_KEYSTORE_BASE64",
    "FRIDAY_SIGNING_STORE_PASSWORD",
    "FRIDAY_SIGNING_KEY_ALIAS",
    "FRIDAY_SIGNING_KEY_PASSWORD",
)


class PreparationError(RuntimeError):
    """A safe-to-display CI configuration error."""


def nonempty(environment: Mapping[str, str], key: str) -> str | None:
    value = environment.get(key)
    return value if value and value.strip() else None


def version_code(value: str | None, now: dt.datetime) -> int:
    """Use an explicit valid code, or UTC seconds since 2020 for CI builds."""
    candidate = value if value is not None and value.strip() else str(int((now.astimezone(dt.timezone.utc) - EPOCH).total_seconds()))
    try:
        parsed = int(candidate)
    except ValueError as error:
        raise PreparationError("FRIDAY_VERSION_CODE must be a positive integer no greater than 2100000000.") from error
    if str(parsed) != candidate or not 1 <= parsed <= MAX_VERSION_CODE:
        raise PreparationError("FRIDAY_VERSION_CODE must be a positive integer no greater than 2100000000.")
    return parsed


def append_environment(path: Path, values: Mapping[str, str]) -> None:
    """Append only non-secret values for later GitHub Actions steps."""
    with path.open("a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def prepare(
    environment: Mapping[str, str],
    runner_temp: Path,
    github_env: Path,
    require_signing: bool,
    now: dt.datetime,
) -> str:
    """Write a configured keystore securely, or select the explicit unsigned path."""
    code = version_code(environment.get("FRIDAY_VERSION_CODE"), now)
    configured = {key: nonempty(environment, key) for key in SIGNING_ENV}
    present = [key for key, value in configured.items() if value is not None]

    if not present:
        if require_signing:
            raise PreparationError(
                "Signing is required for this distribution workflow. Configure all four GitHub Actions secrets: "
                "FRIDAY_SIGNING_KEYSTORE_BASE64, FRIDAY_SIGNING_STORE_PASSWORD, "
                "FRIDAY_SIGNING_KEY_ALIAS, and FRIDAY_SIGNING_KEY_PASSWORD."
            )
        append_environment(github_env, {"FRIDAY_VERSION_CODE": str(code)})
        return (
            "WARNING: no Friday signing secrets are configured; the Friday-PT-BR-debug-APK artifact uses an "
            "ephemeral debug key and cannot update an existing Friday install."
        )

    if len(present) != len(SIGNING_ENV):
        missing = ", ".join(key for key in SIGNING_ENV if key not in present)
        raise PreparationError(f"Partial Friday signing configuration. Missing required secrets: {missing}.")

    try:
        key_bytes = base64.b64decode(configured["FRIDAY_SIGNING_KEYSTORE_BASE64"], validate=True)
    except (binascii.Error, ValueError) as error:
        raise PreparationError("FRIDAY_SIGNING_KEYSTORE_BASE64 is not strict valid Base64.") from error
    if not key_bytes:
        raise PreparationError("FRIDAY_SIGNING_KEYSTORE_BASE64 decoded to an empty keystore.")

    runner_temp.mkdir(parents=True, exist_ok=True)
    store = runner_temp / "friday-ci-signing.keystore"
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    descriptor = os.open(store, flags, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(key_bytes)
    finally:
        try:
            os.chmod(store, 0o600)
        except FileNotFoundError:
            pass

    append_environment(github_env, {
        "FRIDAY_SIGNING_STORE_FILE": str(store),
        "FRIDAY_VERSION_CODE": str(code),
    })
    if stat.S_IMODE(store.stat().st_mode) != 0o600:
        raise PreparationError("Could not restrict the CI keystore file to mode 0600.")
    return "Friday signing key prepared from configured GitHub Actions secrets."


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-signing", action="store_true", help="fail if signing secrets are absent")
    args = parser.parse_args()
    try:
        runner_temp = Path(os.environ["RUNNER_TEMP"])
        github_env = Path(os.environ["GITHUB_ENV"])
    except KeyError as error:
        print(f"::error::{error.args[0]} is required when preparing CI signing.", file=sys.stderr)
        return 2

    try:
        message = prepare(os.environ, runner_temp, github_env, args.require_signing, dt.datetime.now(dt.timezone.utc))
    except PreparationError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1

    print(message)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as output:
            output.write(f"## Friday signing\n\n{message}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
