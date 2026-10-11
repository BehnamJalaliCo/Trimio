# انتشار و نسخه‌های آزمایشی

## اصل مهم: یک کلید برای همیشه

اندروید فقط وقتی نسخهٔ تازه را روی نسخهٔ قبلی نصب می‌کند (بدون پاک کردن اپ و مدل‌های دانلودشده) که هر دو **با یک کلید امضا شده باشند** و **شمارهٔ نسخهٔ تازه بزرگ‌تر باشد**. هر دو را کارگاه‌های GitHub Actions خودکار انجام می‌دهند؛ فقط کلید را یک بار بسازید و در Secrets بگذارید.

> اگر کلید گم شود، دیگر هیچ نسخه‌ای روی نسخهٔ نصب‌شده آپدیت نمی‌شود (و در Google Play هم نمی‌توان اپ را به‌روز کرد). فایل کلید و رمزها را در دو جای امن نگه دارید.

## ساختن کلید (یک بار)

با JDK (هر سیستم‌عاملی):

```
keytool -genkeypair -v -keystore trimio-upload.jks -alias trimio -keyalg RSA -keysize 4096 -validity 36500
```

تبدیل به base64:

- لینوکس/مک: `base64 -w0 trimio-upload.jks > trimio-upload.b64` (در مک: `base64 -i trimio-upload.jks -o trimio-upload.b64`)
- ویندوز (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("trimio-upload.jks")) | Out-File trimio-upload.b64`

## Secrets (GitHub → Settings → Secrets and variables → Actions)

| نام | مقدار | لازم برای |
|---|---|---|
| `TRIMIO_KEYSTORE_BASE64` | محتوای `trimio-upload.b64` | بتا و انتشار اندروید |
| `TRIMIO_KEYSTORE_PASSWORD` | رمز keystore | بتا و انتشار اندروید |
| `TRIMIO_KEY_ALIAS` | `trimio` (همان alias بالا) | بتا و انتشار اندروید |
| `TRIMIO_KEY_PASSWORD` | رمز کلید (اگر جدا نداده‌اید، همان رمز keystore) | بتا و انتشار اندروید |
| `TRIMIO_BRAND_KEY` | کلید رمزگشایی فونت‌های برند | همهٔ ساخت‌ها (بدون آن فونت جایگزین) |
| `TRIMIO_BAZAAR_RSA_KEY` | کلید RSA پنل بازار | اختیاری: پرداخت درون‌برنامه‌ای بازار |
| `WINDOWS_SIGNING_CERT_BASE64` و `WINDOWS_SIGNING_CERT_PASSWORD` | گواهی امضای کد ویندوز (pfx، base64) | اختیاری: نصب‌کنندهٔ ویندوز بدون هشدار SmartScreen |

## نسخه‌ها

- **بتای اندروید** (`android-beta.yml`): هر push روی شاخهٔ کاری یک APK امضاشده با نسخهٔ `0.9.<شماره>-beta` و کد `10000 + شمارهٔ اجرا` در صفحهٔ Releases (پیش‌انتشار `beta-<شماره>`) می‌سازد. روی نسخهٔ قبلی نصب می‌شود؛ مدل‌ها می‌مانند.
- **انتشار** (`release.yml`): برچسب `v1.2.3` ← بستهٔ امضاشدهٔ Play و بازار با کد `1020300`؛ همیشه بالاتر از هر بتا.
- **ویندوز** (`desktop-windows.yml`): نصب‌کنندهٔ MSI با شناسهٔ ارتقای ثابت؛ نسخهٔ تازه نسخهٔ قبلی را جایگزین می‌کند و مدل‌ها در `%APPDATA%\Trimio` می‌مانند.

یک بار آخر: اگر پیش‌تر نسخه‌ای با امضای دیگری (مثلاً debug) روی گوشی نصب است، فقط همان یک بار باید پاکش کرد؛ از آن به بعد همه آپدیت‌اند.
