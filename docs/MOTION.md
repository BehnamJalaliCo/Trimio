# زبان موشن (engine/motion)

چهار لایه؛ کیفیت در لایه‌های پایین زندگی می‌کند، نه در مدل.

| لایه | چه کسی می‌سازد | چیست |
|---|---|---|
| ۰ — موتور | کد | گراف صحنه: کی‌فریم + منحنی، گروه، متن، شمارنده، شکل، فوتیج، افکت؛ ماسک، بلند، سه‌بعدی، دوربین، بلور، موشن‌بلور |
| ۲ — رسپی | کد (صنعت طراح ارشد) | ۱۷ حرکت آماده که زمان‌بندی، overshoot، stagger، هایلایت، خروج، واکنش دوربین و صدا را خودشان تصمیم می‌گیرند |
| ۳ — Score | کارگردان (مدل) | JSON کوچک: کدام رسپی، روی کدام کلمه، با چه انرژی |
| کامپایلر | کد | زمان‌بندی، چیدمان، خوانایی، کپشن، ترنزیشن، دوربین، صدا؛ و کارگردانی خودکار برای صحنه‌های خالی |

## Score

همهٔ فیلدها اختیاری‌اند. کلمه‌ها با شماره (`at`, `until`, `from`) یا با نقل‌قول (`text`) ارجاع می‌شوند.

```json
{
  "look": "noir",            // noir | paper | lumen
  "format": "9:16",          // 9:16 | 1:1 | 4:5 | 16:9
  "bpm": 118,                // ضربه‌ها روی ضرب موسیقی می‌نشینند
  "captions": {"show": true, "maxWords": 4},
  "scenes": [
    {"from": 0, "camera": "push-in", "beats": [
      {"recipe": "slam", "text": "امروز بیت‌کوین", "emphasis": ["بیت‌کوین"], "energy": 0.95},
      {"recipe": "counter", "text": "پنج درصد", "value": 5, "prefix": "+", "suffix": "٪", "label": "رشد امروز", "place": "top"}
    ]},
    {"from": 6, "transition": "whip", "beats": [
      {"recipe": "mask-rise", "text": "سیگنال خرید ما", "emphasis": ["سیگنال خرید"]}
    ]}
  ]
}
```

- `place`: top | center | lower | full — اگر جا پر باشد کامپایلر جای دیگری پیدا می‌کند.
- `transition`: cut | whip | zoom | flash | leak — `camera`: push-in | pull-out | drift | still
- `bg`: media | aurora | grid | plain — `mark`: block | ink | underline | circle

## تصویر هر مفهوم (همهٔ حوزه‌ها)

کارگردان فقط می‌گوید چه چیزی دیده شود، به انگلیسی؛ موتور از واژگان بصری (حدود ۱۸ هزار نماد آزاد: ایموجی رنگی، پزشکی، Material، Tabler) بهترینش را پیدا می‌کند:

```json
{"recipe": "object", "text": "متخصص قلب", "visual": "anatomical heart", "label": "قلب"}
{"recipe": "objects", "items": ["پاستا", "خامه", "سیر"], "visuals": ["spaghetti", "glass of milk", "garlic"]}
{"recipe": "logos", "items": ["Claude Code", "Codex"]}
{"recipe": "voucher", "value": 350, "suffix": "تتر", "label": "سرمایه اولیه", "items": ["Tether"]}
{"recipe": "countdown", "value": 48, "suffix": "ساعت", "label": "تا پایان کمپین"}
{"recipe": "stats", "items": ["بازدید", "کامنت"], "points": [50000, 5000]}
{"recipe": "progress", "value": 100, "label": "۱۰۰۰ نفر اول", "text": "تکمیل"}
```

## رسپی‌ها

متن: `mask-rise` `slam` `pop-captions` `type-on` `blur-in` `flip` `spread` `stack` `glitch`
المان: `counter` `ticker` `chart` `bars` `icon` `lower-third` `stamp` `list`
توضیحی: `chips` `terminal` `network` `meter` `comment` — برند: `logos` — شیء: `object` `objects`
داده و تبلیغ: `countdown` (شمارش معکوس مهلت) `stats` (چند آمار کنار هم) `progress` (نوار ظرفیت با مهر «تکمیل») `voucher` (کارت ووچر با لوگوی ارز)

نام‌های تقریبی هم پذیرفته می‌شوند (مثلاً impact → slam، caption → pop-captions، price → ticker).
آیکون‌ها: arrow-up/down، trend-up/down، check، bolt، fire، star، bell، target، coin، lock، clock، heart، warning، rocket، eye، spark و نام‌های مترادف (btc، pump، dump، signal، tp…).

## قواعدی که کامپایلر همیشه اعمال می‌کند

- ورود دو فریم زودتر از کلمه، تا کلمه هم‌زمان با گفتن «بنشیند»
- حداقل زمان خوانایی بر اساس طول متن و زمان لازم هر رسپی
- هر ناحیه در هر لحظه یک چیز؛ برخورد ← جابه‌جایی یا خروج زودتر (بعد از خوانده‌شدن)
- کپشن وقتی تیتر روی صفحه است کنار می‌رود؛ در هر خط فقط یک هایلایت
- علائم پایان جمله از متن موشن حذف می‌شوند
- اعداد گفته‌شده (فارسی و انگلیسی) به شمارنده تبدیل می‌شوند؛ کلمات کلیدی آیکون می‌گیرند
- صداها بدون تداخل (حداقل ۰٫۱۲ ثانیه فاصله)
