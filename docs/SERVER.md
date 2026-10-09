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

## Paywall switch

Everything is free until `RemoteConfig.flags.pro = true` **and** `proProductId` names a product
that exists in the Play Console / Bazaar panel. Prices live only in the store consoles.
Turning the flag off makes everything free again on the next start.
