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

On CI, the same values come from secrets as the environment variables
`FRIDAY_SIGNING_STORE_FILE`, `FRIDAY_SIGNING_STORE_PASSWORD`,
`FRIDAY_SIGNING_KEY_ALIAS` and `FRIDAY_SIGNING_KEY_PASSWORD`.

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
