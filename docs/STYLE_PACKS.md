# پکیج‌های سبک (Style Packs)

هر سبک یک فایل JSON است؛ بدون هیچ کد اجرایی (سیاست گوگل‌پلی). سبک جدید یا نسخهٔ بهتر یک سبک، **بدون آپدیت اپ** منتشر می‌شود.

## ساختار

```jsonc
{
  "schema": 1,                    // نسخهٔ قالب؛ اپ نسخه‌های جدیدتر را رد می‌کند
  "id": "liquid-glass",           // kebab-case؛ یکی از ۲۸ سبک یا سبک جدید
  "version": "1.0.0",             // نسخهٔ بالاتر جایگزین نسخهٔ نصب‌شده می‌شود
  "nameFa": "...", "nameEn": "...",
  "descriptionFa": "...", "descriptionEn": "...",
  "family": "ShaderFx",           // Vector2D | ShaderFx | Typography | TextureCollage | ThreeD | Organic
  "cost": "Heavy",                // Light | Medium | Heavy (حداقل سخت‌افزار برای پیش‌نمایش زنده)
  "tags": ["premium", "کریپتو"],  // کارگردان پرامپت را با این‌ها تطبیق می‌دهد (فارسی و انگلیسی)
  "spec": { /* StyleSpec */ }
}
```

### `spec` (قرارداد رندر — `core/model/style/StyleSpec.kt`)

| بخش | فیلدهای مهم |
|---|---|
| `palette` | `background` (۱ تا ۴ رنگ)، `text`، `accent`، `accent2`، `emphasisText`، `shadow` — `#RRGGBB` یا `#RRGGBBAA` |
| `captions` | `mode` (BuildUp / Phrase / Karaoke / SingleWord)، `size` (کسری از ضلع کوتاه)، `weight`، `entry` (pop/rise/slam/wipe/flip/fade)، `exit` (fade/fall/shrink)، `box` (None/Pill/Glass/Brutal)، `boxColor`، `emphasis` (scale، colorRole، box: None/Highlight، threshold) |
| `background` | `preset`: aurora / gradient / mesh / grid / waves / solid / `shader:<name>` |
| `overlay` | `grain`، `vignette`، `letterbox` |
| `elements` | `card`: glass / solid / brutal / outline، `cornerRadius` |
| `motion` | `energy` (۰ آرام تا ۱ هیجانی)، `cameraPunch` |
| `audioOnly` | `captionAnchor`، `captionScale`، `visualizer` (ring / bars / none) |
| `shaders` | شیدرهای SkSL/AGSL با یونیفرم‌های استاندارد `iResolution, iTime, iEnergy, cBase, cA, cB, cC` |
| `sfx` | رویداد ← صدا، مثلاً `"caption.emphasis": "sfx/pop"` |

قواعد قابل‌حمل بودن شیدر: فقط اعداد اعشاری (`1.0`)، حلقه با کران ثابت، خروجی premultiplied، و هرگز `smoothstep` با لبه‌های برعکس.

## اعتبارسنجی

`StylePackValidator` قبل از رسیدن پکیج به رندرر همه‌چیز را بررسی می‌کند: شناسه، نسخه، رنگ‌ها، بازه‌ها، پریست‌های شناخته‌شده، وجود شیدر ارجاع‌شده. پکیج نامعتبر «نصب نمی‌شود»، هرگز باعث کرش یا خروجی خراب نمی‌شود.

## پیش‌نمایش برای طراح

```bash
./gradlew :engine:styles:stylePreview -Ppack=engine/styles/src/commonMain/composeResources/files/styles/liquid-glass.json
# → engine/styles/build/style-tool/liquid-glass-sheet.png (۳ لحظه × فوتیج و فقط-صدا)
```

## امضا و انتشار

پکیج‌های داخل اپ مورد اعتمادند. پکیج‌های دانلودی باید **امضای ECDSA P-256** داشته باشند:

```bash
# یک بار: ساخت کلید (کلید خصوصی فقط روی سرور/CI، هرگز در مخزن)
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -outform DER -out pack-signing.der
openssl pkey -inform DER -in pack-signing.der -pubout -outform DER | base64 -w0   # ← کلید عمومی

# امضای هر پکیج
./gradlew -q :engine:styles:stylePreview -Psign=my-style.json -Pkey=pack-signing.der -PkeyId=trimio-2026 > my-style.signed.json
```

کلید عمومی در شروع اپ ثبت می‌شود: `PackKeys.trusted["trimio-2026"] = <bytes>`. تا قبل از ثبت کلید، فقط پکیج‌های داخلی بارگذاری می‌شوند (پیش‌فرض امن).

پاکت امضاشده:

```json
{ "format": "trimio-style-pack/1", "keyId": "trimio-2026", "payload": "<base64 JSON>", "signature": "<base64 DER>" }
```

امضا روی بایت‌های خود payload است، پس به قالب‌بندی یا ترتیب کلیدهای JSON وابسته نیست.
