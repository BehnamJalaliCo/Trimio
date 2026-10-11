# سرور کاتالوگ / Catalogue server

سرور فقط **داده** پخش می‌کند: پیکربندی از راه دور، کاتالوگ مدل‌ها و پکیج‌های سبکِ امضاشده.
هیچ ویدیو، صدا، پرامپت یا متنی از کاربر به این سرور نمی‌رسد و اپ هیچ شناسه‌ای نمی‌فرستد.

The server only distributes data. The app sends no identifiers and never uploads media, prompts or transcripts.

| Route | Auth | |
|---|---|---|
| `GET /v1/config` | – | `RemoteConfig`: feature flags, minimum build, revoked pack keys, announcement, paid product |
| `GET /v1/catalog` | – | extra/updated models + published style versions |
| `GET /v1/packs/{id}` | – | signed pack envelope, byte for byte, with `ETag` |
| `POST /v1/admin/packs` | Bearer | upload a **signed** envelope; rejected unless it verifies with `TRIMIO_PACK_KEYS` |
| `PUT /v1/admin/config` | Bearer | replace `RemoteConfig` |
| `PUT /v1/admin/models` | Bearer | replace the extra model list |
| `GET /health` | – | liveness |

## Run

```bash
./gradlew :server:installDist
docker build -t trimio-server server
docker run -p 8080:8080 -v trimio-data:/srv/trimio/data \
  -e TRIMIO_ADMIN_TOKEN="$(openssl rand -hex 32)" \
  -e TRIMIO_PACK_KEYS="trimio-2026=<base64 DER public key>" trimio-server
```

Put it behind HTTPS (`api.trimio.app`). The app reaches it at start, caches the answer, and works
fully offline with the last good copy (or the built-in defaults).

## Security model

- **Packs are signed offline** (`./gradlew :engine:styles:stylePreview -Psign=… -Pkey=… -PkeyId=…`).
  The private key never touches the server; the server re-verifies every pack it serves.
- **Trusted keys ship inside the app.** Remote config can only *revoke* a key
  (`revokedPackKeys`), never add one, so a compromised server cannot push shader code.
- Remote models must use HTTPS URLs and carry a SHA-256; the download is verified before native code loads it.
- Admin token: at least 32 characters, compared in constant time. Without it the admin routes are closed.
- CORS: any origin may *read* the public routes (the web app needs them); the `Authorization` header is
  not allowed cross-origin, so no web page can call the admin routes.

## Paywall switch

Everything is free until `RemoteConfig.flags.pro = true` **and** `proProductId` names a product
that exists in the Play Console / Bazaar panel. Prices live only in the store consoles.
Turning the flag off makes everything free again on the next start.

## Downloadable packs (Ed25519) / بسته‌های دانلودی

Style, sound, music and model packs update separately from the app (`engine/models/Packs.kt`).
A pack is a `.tpk` container (`PackArchive`: relative paths only, no compression) described by a
`PackManifest` in the index: `id`, `kind`, `version` (only ever increases), `size`, `sha256`,
mirror `urls`, `minApp`, `license`, `keyId` and an Ed25519 `signature` over
`trimio-pack/1\n<id>\n<kind>\n<version>\n<size>\n<sha256>\n<minApp>\n<license>`.

The app (`PackStore`) verifies the signature against keys pinned in the build **before downloading**,
checks size and SHA-256, unpacks into a staging folder, moves it into place atomically and only then
switches; the previous release is kept for `rollback`. Older versions are refused (no replay).
The APK keeps a small offline core (`PackCatalog.core`: procedural music and SFX, bundled styles);
everything heavier is a pack.

Key handling:

- **No private key is ever in this repository.** Tests generate a throwaway keypair per run
  (`PackStoreTest`).
- Generate the release key offline on a trusted machine, e.g. `openssl genpkey -algorithm ed25519 -out pack-2026.pem`;
  keep it in a hardware token or the CI secret store (`TRIMIO_PACK_SIGNING_KEY`), never on the catalogue server.
- Publish only the raw 32-byte public key (`openssl pkey -in pack-2026.pem -pubout -outform DER | tail -c 32 | xxd -p -c 64`)
  and pin it in the app build by key id. Rotation: ship the new key id in an app release first, then sign with it;
  revoke the old id through `revokedPackKeys` (remote config can revoke, never add).
- Sign in CI from the artefact's bytes: compute size and SHA-256, build the signed string, sign, upload the manifest
  and payload. The server re-verifies with its copy of the public keys before serving.

