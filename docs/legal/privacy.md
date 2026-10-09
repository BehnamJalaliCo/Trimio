# حریم خصوصی Trimio

**تاریخ اجرا:** ۱۷ مهر ۱۴۰۵ (۹ اکتبر ۲۰۲۶)

Trimio طوری ساخته شده که ویدیو و صدای شما **روی گوشی خودتان** پردازش شود. ما حساب کاربری نمی‌سازیم، تبلیغ نمایش نمی‌دهیم و داده‌ای نمی‌فروشیم.

## چه چیزی روی گوشی می‌ماند
- ویدیو، صدا، متن گفتار (زیرنویس)، پرامپت و پروژه‌ها فقط در حافظه‌ی اپ روی گوشی ذخیره می‌شوند.
- تشخیص گفتار (whisper.cpp) و کارگردان پیش‌فرض (مدل زبانی روی گوشی) بدون اینترنت اجرا می‌شوند.
- خروجی‌ها فقط وقتی شما بخواهید در گالری ذخیره یا اشتراک‌گذاری می‌شوند.

## وقتی خودتان کارگردان ابری را روشن کنید
اگر کلید API خودتان (Anthropic Claude یا OpenAI) را وارد کنید و کارگردان ابری را انتخاب کنید، برای هر پروژه این موارد **مستقیم از گوشی شما** به همان سرویس فرستاده می‌شود: پرامپت، متن گفتار با زمان‌بندی کلمات، مدت و نوع ورودی، و فهرست سبک‌ها. خود ویدیو و صدا فرستاده نمی‌شوند. این درخواست‌ها از سرورهای ما عبور نمی‌کنند و تابع سیاست حریم خصوصی همان سرویس هستند. کلید شما رمزنگاری‌شده در Android Keystore می‌ماند و هرگز برای ما فرستاده نمی‌شود.

## ارتباط با سرورهای ما
- **کاتالوگ:** اپ در شروع، پیکربندی، فهرست مدل‌ها و پکیج‌های سبک را از `api.trimio.app` می‌گیرد. هیچ شناسه، اطلاعات حساب یا محتوایی فرستاده نمی‌شود؛ سرور مثل هر وب‌سروری آدرس IP و نوع درخواست را در لاگ کوتاه‌مدت نگه می‌دارد.
- **دانلود مدل:** فایل‌های مدل از CDN ما یا Hugging Face دانلود می‌شوند.

## گزارش خطا
گزارش خطا فقط روی گوشی ذخیره می‌شود (متن فنی خطا، مدل گوشی و نسخه‌ی اپ). فقط اگر خودتان در تنظیمات اجازه دهید و دکمه‌ی ارسال را بزنید، از طریق برگه‌ی اشتراک‌گذاری اندروید فرستاده می‌شود.

## پرداخت
خرید درون‌برنامه‌ای کاملاً توسط Google Play یا کافه‌بازار انجام می‌شود. ما اطلاعات کارت یا حساب بانکی شما را نمی‌بینیم؛ اپ فقط وضعیت خرید (خریده‌شده یا نه) را از فروشگاه می‌پرسد.

## دسترسی‌ها
- **اینترنت:** کاتالوگ، دانلود مدل و (در صورت انتخاب) کارگردان ابری.
- **اعلان‌ها:** نمایش پیشرفت ساخت و دانلود.
- **انتخاب‌گر عکس و ویدیو:** فقط به فایلی که خودتان انتخاب می‌کنید دسترسی داریم؛ اپ مجوز کلی خواندن حافظه ندارد.

## کودکان
Trimio برای کودکان زیر ۱۳ سال طراحی نشده است.

## حذف داده
همه‌ی داده‌ها روی گوشی شماست: با حذف پروژه یا پاک کردن داده‌ی اپ از تنظیمات اندروید، همه چیز پاک می‌شود. ما نسخه‌ای از آن نداریم.

## تماس
پرسش‌های حریم خصوصی: `[ایمیل پشتیبانی — پیش از انتشار تکمیل شود]`

---

# Trimio Privacy Policy

**Effective:** 9 October 2026

Trimio processes your video and voice **on your own phone**. There are no accounts, no ads, and we sell no data.

## What stays on your phone
- Videos, audio, transcripts, prompts and projects are stored only in the app's storage on your device.
- Speech recognition (whisper.cpp) and the default director (an on-device language model) run offline.
- Exports are saved or shared only when you choose to.

## If you turn on the cloud director
If you enter your own API key (Anthropic Claude or OpenAI) and choose the cloud director, then for each project the following goes **directly from your phone** to that provider: your prompt, the transcript with word timings, the input's duration and type, and the style list. Your video and audio are not sent. These requests do not pass through our servers and are governed by that provider's privacy policy. Your key is stored encrypted in the Android Keystore and is never sent to us.

## Our servers
- **Catalogue:** at start the app fetches configuration, the model list and style packs from `api.trimio.app`. No identifiers, account data or content are sent; like any web server it keeps IP addresses and request lines in short-lived logs.
- **Model downloads:** model files come from our CDN or Hugging Face.

## Crash reports
Crash reports are stored on the phone only (the technical error, phone model and app version). They are sent only if you enable crash sharing in settings and tap send, through Android's share sheet.

## Payments
In-app purchases are handled entirely by Google Play or Cafe Bazaar. We never see card or bank details; the app only asks the store whether a product is owned.

## Permissions
- **Internet:** catalogue, model downloads and (if chosen) the cloud director.
- **Notifications:** build and download progress.
- **Photo and video picker:** we can read only the file you pick; the app has no broad storage permission.

## Children
Trimio is not directed at children under 13.

## Deleting data
All data lives on your device. Deleting a project, or clearing the app's data in Android settings, removes it. We hold no copy.

## Contact
Privacy questions: `[support email — fill in before publishing]`
