# Signing key

`friday.keystore` signs the owner's Friday builds, debug and release. It is
**not in the repository** (see `.gitignore`), and neither are its passwords.

## Configuring a machine

Add to `local.properties` (also never committed):

```properties
friday.signing.storeFile=keystore/friday.keystore
friday.signing.storePassword=…
friday.signing.keyAlias=…
friday.signing.keyPassword=…
```

## Configuring GitHub Actions

In GitHub, open **Settings → Secrets and variables → Actions → New repository
secret**. Add these four secrets exactly:

| Secret | Value |
|---|---|
| `FRIDAY_SIGNING_KEYSTORE_BASE64` | Base64 of the original `friday.keystore` (for example, `base64 -w 0 keystore/friday.keystore`) |
| `FRIDAY_SIGNING_STORE_PASSWORD` | Keystore password |
| `FRIDAY_SIGNING_KEY_ALIAS` | Key alias |
| `FRIDAY_SIGNING_KEY_PASSWORD` | Key password |

The workflow decodes the keystore only into the runner's temporary directory
with mode `0600`; it does not commit it or print secrets. It supplies the
temporary path as `FRIDAY_SIGNING_STORE_FILE`. Keep passwords out of chat,
issues, logs, and source control.

`android-apk.yml` is a distribution workflow and fails if any secret is
missing. `ci.yml` intentionally still tests pull requests without secrets; it
warns in the job summary that its ephemeral-debug-key artifact cannot update
an installed Friday.

## What gets built

| | Signing configured | Not configured |
|---|---|---|
| `assembleDebug` | owner's key | this machine's SDK debug key — fine for trying Friday out |
| `assembleRelease` | owner's key | **unsigned**: builds and passes the R8 checks (CI), but cannot be installed |
| `ownerRelease` | owner's key, R8-checked | **fails** before building |

Releases to install or publish come from `./gradlew ownerRelease`
(`app/build/outputs/apk/release/app-release.apk`). A debug key is generated
per machine, so a build from another machine — or an unsigned one — can never
update the owner's install by accident.

## Back this folder up

It is the identity of the app:

- Android only installs an update over an existing Friday if it is signed with
  the same key. A build signed with any other key has to replace the app, and
  replacing it wipes its data — memory, voice profile, settings, chat history.
- Gmail access is registered in Google Cloud against this key's SHA-1
  (`4B:B3:CB:54:33:D6:E0:EC:B8:4B:4F:AE:F8:15:40:D7:59:F9:82:96`).

The key was first generated as the Android SDK debug key on the machine Friday
was first built on. Its private key exists only in this file and its backups;
it is fine for an app installed on its owner's phone. A store-published build
would need a dedicated release key.

There is no way to recover this private key from an APK. To update an existing
install, restore and use the same original keystore (or its backup).
