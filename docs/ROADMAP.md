# نقشهٔ راه

## فاز ۰ — پایه
- [x] اسکلت Kotlin Multiplatform (Android، JVM، iOS، Wasm) + convention plugins
- [x] CI
- [x] `core/model`: Timeline DSL، رونوشت، ورودی ویدیو/صدا، ۲۸ سبک
- [x] `core/pipeline`: Orchestrator، پیشرفت وزن‌دار، چک‌پوینت، پایپ‌لاین نمایشی
- [ ] `core/designsystem`: Trimio DS (توکن‌ها، فونت Vazirmatn، شیدرهای Aurora/Liquid، شیشه)
- [ ] `feature/stream`: صفحهٔ استریم ساخت ۰ تا ۱۰۰٪
- [ ] `androidApp`: اجرای صفحهٔ استریم با پایپ‌لاین نمایشی

## فاز ۱ — صدا و گفتار
- [ ] `engine/media`: Media3 (ورودی، پیش‌نمایش، خروجی)
- [ ] `engine/audio`: پاک‌سازی نویز، نرمال‌سازی، حذف سکوت، تأکید
- [ ] `engine/asr`: whisper.cpp + تراز کلمه‌ای
- [ ] زیرنویس کلمه‌به‌کلمهٔ فارسی/انگلیسی

## فاز ۲ — رندر و سبک‌ها
- [ ] `engine/render`: هستهٔ C++ (Skia، Skottie، Filament) به‌عنوان GlEffect در Media3
- [ ] فرمت پکیج سبک + ۳ سبک اول (Kinetic Typography، Neobrutalism، Liquid Glass)
- [ ] حالت فقط-صدا: بوم کامل ساخته‌شده توسط سبک

## فاز ۳ — کارگردان
- [ ] `engine/llm`: llama.cpp + آداپتور ابری
- [ ] Director و کنترل کیفیت
- [ ] کتابخانهٔ المان و افکت صوتی + جست‌وجوی معنایی

## فاز ۴ — محصول
- [ ] ادیتور متن‌محور و تایم‌لاین
- [ ] ۲۸ سبک
- [ ] خروجی XML پریمیر/داوینچی
- [ ] Billing، CDN، انتشار
