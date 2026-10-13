#!/usr/bin/env python3
"""Builds the bundled illustration library (engine/illustration composeResources files/illustration/).

Inputs (downloaded by hand, never committed):
  --peeps   JSON of Open Peeps pieces rendered to SVG markup (tools/peeps.js over the react-peeps npm package;
            drawings by Pablo Stanley, CC0 1.0; package code MIT)
  --fluent  @iconify-json/fluent-emoji-flat icons.json (Microsoft Fluent Emoji, MIT)
  --health  @iconify-json/healthicons icons.json (Resolve to Save Lives, MIT)

Outputs: people.json (the Open Peeps pieces, numbers rounded to 1 decimal) and objects.json (curated objects
and places with English and Persian keywords). Usage:
  python3 -I build_library.py --peeps peeps_raw.json --fluent icons.json --health icons.json --out <dir>
"""
import argparse
import json
import re

# id, source:name, kind, English keywords, Persian keywords. Kinds: object, place, symbol, food, health, transport, nature.
OBJECTS = [
    # --- drawn for Trimio
    ("safe", "trimio:safe", "object", "safe vault strongbox deposit savings secure gold storage", "گاوصندوق صندوق امانات سپرده پس‌انداز امن نگهداری طلا"),
    ("gold-bars", "trimio:gold-bars", "object", "gold bars bullion ingot gold investment", "طلا شمش شمش طلا سرمایه‌گذاری طلا"),
    ("storefront", "trimio:storefront", "place", "store shop storefront retail business company branch office exchange", "فروشگاه مغازه کسب و کار شرکت شعبه دفتر صرافی"),
    # --- money and finance
    ("money-bag", "fluent:money-bag", "object", "money bag cash savings wealth fund capital profit", "پول کیسه پول سرمایه ثروت پس‌انداز سود صندوق"),
    ("banknote", "fluent:dollar-banknote", "object", "banknote cash money bill dollar payment salary", "اسکناس پول نقد دلار حقوق دستمزد پرداخت"),
    ("euro", "fluent:euro-banknote", "object", "euro banknote currency", "یورو ارز اسکناس"),
    ("money-wings", "fluent:money-with-wings", "object", "losing money spending inflation expense loss", "تورم هزینه خرج ضرر از دست دادن پول پرواز پرنده"),
    ("coin", "fluent:coin", "object", "coin gold gold coin bullion savings price", "سکه طلا سکه طلا شمش قیمت"),
    ("gem", "fluent:gem-stone", "object", "gem diamond jewel valuable asset precious", "الماس جواهر گوهر ارزشمند دارایی"),
    ("credit-card", "fluent:credit-card", "object", "card bank card credit card payment debit", "کارت کارت بانکی کارت اعتباری پرداخت عابربانک"),
    ("chart-up", "fluent:chart-increasing", "symbol", "chart growth rise increase market up profit stock", "نمودار رشد افزایش صعود بازار بورس سهام سود بالا"),
    ("chart-down", "fluent:chart-decreasing", "symbol", "chart fall drop decrease crash down loss recession", "نمودار سقوط کاهش افت ریزش نزول ضرر رکود بازار بورس"),
    ("bar-chart", "fluent:bar-chart", "symbol", "bar chart statistics data report numbers compare", "آمار نمودار میله‌ای داده گزارش ارقام مقایسه"),
    ("exchange", "fluent:currency-exchange", "symbol", "exchange currency rate convert dollar rial", "صرافی نرخ ارز تبدیل دلار ریال تومان"),
    ("balance", "fluent:balance-scale", "object", "scale balance justice law court fair compare weigh", "ترازو عدالت قانون دادگاه انصاف مقایسه سنجیدن"),
    ("abacus", "fluent:abacus", "object", "abacus calculate count accounting", "چرتکه حساب محاسبه حسابداری شمردن"),
    ("receipt", "fluent:receipt", "object", "receipt invoice bill purchase tax", "رسید فاکتور قبض صورتحساب خرید مالیات"),
    ("ledger", "fluent:ledger", "object", "ledger accounts book records", "دفتر حساب دفتر کل سوابق"),
    ("atm", "fluent:atm-sign", "symbol", "atm cash machine withdrawal", "خودپرداز عابربانک برداشت"),
    ("locked", "fluent:locked", "object", "lock locked secure blocked frozen safe security", "قفل مسدود امن بلوکه امنیت قفل شده"),
    ("locked-key", "fluent:locked-with-key", "object", "safe vault lock key secure deposit", "گاوصندوق صندوق امانات قفل کلید سپرده"),
    ("unlocked", "fluent:unlocked", "object", "unlocked open released free access", "باز آزاد دسترسی آزادسازی"),
    ("key", "fluent:key", "object", "key solution access password", "کلید راه حل دسترسی رمز"),
    ("briefcase", "fluent:briefcase", "object", "briefcase business work job office career", "کیف کار شغل اداره کسب و کار حرفه"),
    ("handshake", "fluent:handshake", "object", "handshake deal agreement contract partnership trust", "دست دادن توافق قرارداد معامله همکاری اعتماد"),
    ("shopping-cart", "fluent:shopping-cart", "object", "shopping cart buy purchase store consumer", "سبد خرید خرید فروشگاه مصرف‌کننده"),
    ("shopping-bags", "fluent:shopping-bags", "object", "shopping bags buy sale customer", "کیسه خرید خرید حراج مشتری"),
    ("package", "fluent:package", "object", "package box parcel delivery shipment product", "بسته جعبه مرسوله ارسال کالا محصول"),
    ("purse", "fluent:purse", "object", "wallet purse money pocket", "کیف پول جیب"),
    ("calendar", "fluent:calendar", "object", "calendar date deadline day schedule business days", "تقویم تاریخ مهلت روز برنامه روز کاری"),
    ("tear-calendar", "fluent:tear-off-calendar", "object", "calendar day date countdown", "تقویم روز تاریخ شمارش"),
    ("hourglass", "fluent:hourglass-not-done", "object", "hourglass waiting time delay pending", "ساعت شنی انتظار زمان تاخیر معطل در انتظار"),
    ("stopwatch", "fluent:stopwatch", "object", "stopwatch speed fast time timer", "زمان سنج سرعت سریع زمان"),
    ("alarm-clock", "fluent:alarm-clock", "object", "alarm clock time wake deadline", "ساعت زنگ‌دار زمان بیدار مهلت"),
    ("clipboard", "fluent:clipboard", "object", "clipboard checklist report form list", "گزارش فرم فهرست چک‌لیست"),
    ("memo", "fluent:memo", "object", "note memo document write letter contract", "یادداشت سند نامه قرارداد نوشتن"),
    ("page", "fluent:page-facing-up", "object", "document paper page file report", "سند کاغذ برگه پرونده گزارش"),
    ("folder", "fluent:file-folder", "object", "folder file documents archive", "پوشه پرونده اسناد بایگانی"),
    ("newspaper", "fluent:newspaper", "object", "newspaper news headline press report media", "روزنامه خبر تیتر مطبوعات گزارش رسانه"),
    ("envelope", "fluent:envelope", "object", "envelope mail letter message", "پاکت نامه پیام"),
    ("email", "fluent:e-mail", "object", "email message inbox", "ایمیل پیام صندوق"),
    ("megaphone", "fluent:megaphone", "object", "megaphone announcement marketing advertise shout", "بلندگو اعلام تبلیغ بازاریابی اطلاع‌رسانی"),
    ("loudspeaker", "fluent:loudspeaker", "object", "loudspeaker announcement public", "بلندگو اعلامیه عمومی"),
    ("bell", "fluent:bell", "object", "bell notification alert reminder", "زنگ اعلان هشدار یادآوری"),
    ("warning", "fluent:warning", "symbol", "warning danger risk caution alert scam", "هشدار خطر ریسک احتیاط کلاهبرداری"),
    ("prohibited", "fluent:prohibited", "symbol", "forbidden banned not allowed stop no", "ممنوع غیرمجاز توقف نه"),
    ("stop-sign", "fluent:stop-sign", "symbol", "stop halt end", "ایست توقف پایان"),
    ("check", "fluent:check-mark-button", "symbol", "done correct yes approved success", "تایید درست بله موفق انجام شد"),
    ("cross", "fluent:cross-mark", "symbol", "wrong no rejected error fail", "غلط نه رد خطا شکست"),
    ("question", "fluent:red-question-mark", "symbol", "question why doubt unknown", "سوال چرا ابهام نامعلوم"),
    ("exclamation", "fluent:red-exclamation-mark", "symbol", "important attention surprise", "مهم توجه تعجب"),
    ("light-bulb", "fluent:light-bulb", "object", "idea light bulb insight solution creative", "ایده لامپ فکر راه حل خلاقیت"),
    ("bullseye", "fluent:bullseye", "object", "target goal aim focus objective", "هدف نشانه تمرکز"),
    ("trophy", "fluent:trophy", "object", "trophy win winner success champion best", "جام برنده پیروزی موفقیت قهرمان بهترین"),
    ("medal", "fluent:1st-place-medal", "object", "medal first place gold winner", "مدال مقام اول برنده"),
    ("crown", "fluent:crown", "object", "crown king best premium leader", "تاج پادشاه بهترین ویژه رهبر"),
    ("puzzle", "fluent:puzzle-piece", "object", "puzzle piece solution fit problem", "پازل قطعه راه حل مسئله"),
    ("magnifier", "fluent:magnifying-glass-tilted-left", "object", "search magnifying glass investigate research find inspect", "جستجو ذره‌بین بررسی تحقیق پیدا کردن بازرسی"),
    ("shield", "fluent:shield", "object", "shield protection security insurance defend safe", "سپر محافظت امنیت بیمه دفاع امن"),
    ("gear", "fluent:gear", "object", "gear settings process mechanism system engine", "چرخ‌دنده تنظیمات فرایند سازوکار سیستم"),
    ("wrench", "fluent:hammer-and-wrench", "object", "tools fix repair build maintenance", "ابزار تعمیر ساختن نگهداری"),
    ("toolbox", "fluent:toolbox", "object", "toolbox tools kit", "جعبه ابزار ابزار"),
    ("link", "fluent:link", "object", "link chain connection connect", "پیوند لینک اتصال زنجیر"),
    ("broken-chain", "fluent:broken-chain", "object", "broken chain disconnect break free", "زنجیر پاره قطع اتصال رهایی"),
    ("magnet", "fluent:magnet", "object", "magnet attract attraction pull", "آهنربا جذب کشش"),
    ("rocket", "fluent:rocket", "object", "rocket launch startup fast growth boost", "موشک پرتاب استارتاپ رشد سریع جهش"),
    ("fire", "fluent:fire", "symbol", "fire hot trend burn crisis", "آتش داغ ترند سوختن بحران"),
    ("lightning", "fluent:high-voltage", "symbol", "lightning energy power electricity fast", "صاعقه انرژی برق نیرو سریع"),
    ("bomb", "fluent:bomb", "object", "bomb explosion danger crisis", "بمب انفجار خطر بحران"),
    ("collision", "fluent:collision", "symbol", "crash collision impact boom", "برخورد ضربه انفجار"),
    ("battery", "fluent:battery", "object", "battery energy charge power", "باتری انرژی شارژ"),
    ("low-battery", "fluent:low-battery", "object", "low battery tired empty drained", "باتری ضعیف خسته خالی تمام شده"),
    ("plug", "fluent:electric-plug", "object", "plug electricity power connect", "دوشاخه برق اتصال"),
    ("scroll", "fluent:scroll", "object", "scroll law history decree", "طومار قانون تاریخ فرمان"),
    ("ballot", "fluent:ballot-box-with-ballot", "object", "vote election ballot democracy", "رای انتخابات صندوق رای دموکراسی"),
    ("judge", "fluent:judge", "object", "judge court verdict law", "قاضی دادگاه حکم قانون"),
    ("id-card", "fluent:identification-card", "object", "id card identity identification verify kyc", "کارت شناسایی هویت احراز هویت"),
    ("passport", "fluent:passport-control", "symbol", "passport travel border visa", "گذرنامه سفر مرز ویزا"),
    ("ticket", "fluent:admission-tickets", "object", "ticket entry event", "بلیت ورود رویداد"),
    ("gift", "fluent:wrapped-gift", "object", "gift present bonus reward", "هدیه جایزه پاداش"),
    ("label", "fluent:label", "object", "label tag price sale discount", "برچسب قیمت حراج تخفیف"),
    ("books", "fluent:books", "object", "books learning study knowledge education library", "کتاب یادگیری مطالعه دانش آموزش کتابخانه"),
    ("open-book", "fluent:open-book", "object", "book read story lesson", "کتاب خواندن داستان درس"),
    ("graduation", "fluent:graduation-cap", "object", "graduation education university degree student", "فارغ‌التحصیلی آموزش دانشگاه مدرک دانشجو"),
    ("pencil", "fluent:pencil", "object", "pencil write draw plan", "مداد نوشتن طراحی برنامه"),
    ("pen", "fluent:fountain-pen", "object", "pen sign signature contract write", "خودکار امضا قرارداد نوشتن"),
    ("pushpin", "fluent:round-pushpin", "object", "location pin place map point", "مکان نشانگر نقشه نقطه"),
    ("world-map", "fluent:world-map", "object", "world map global countries", "نقشه جهان جهانی کشورها"),
    ("globe", "fluent:globe-showing-europe-africa", "object", "globe world earth global international", "کره زمین جهان جهانی بین‌المللی"),
    ("globe-net", "fluent:globe-with-meridians", "symbol", "internet web global network online", "اینترنت وب شبکه جهانی آنلاین"),
    ("compass", "fluent:compass", "object", "compass direction navigation strategy", "قطب‌نما جهت مسیر راهبرد"),
    ("ladder", "fluent:ladder", "object", "ladder climb steps career progress", "نردبان بالا رفتن پله پیشرفت"),
    ("door", "fluent:door", "object", "door entrance opportunity exit", "در ورود فرصت خروج"),
    ("chains", "fluent:chains", "object", "chains debt trapped restriction", "زنجیر بدهی گرفتار محدودیت"),
    ("thought", "fluent:thought-balloon", "symbol", "thinking thought idea wonder", "فکر کردن اندیشه ایده"),
    ("speech", "fluent:speech-balloon", "symbol", "speech talk chat message comment", "گفتگو حرف چت پیام نظر"),
    ("thumbs-up", "fluent:thumbs-up", "symbol", "like approve good agree", "لایک تایید خوب موافق"),
    ("thumbs-down", "fluent:thumbs-down", "symbol", "dislike bad disagree", "بد مخالف نپسندیدن"),
    ("heart", "fluent:red-heart", "symbol", "love heart like care", "عشق قلب دوست داشتن مراقبت"),
    ("broken-heart", "fluent:broken-heart", "symbol", "broken heart sad loss", "دل شکسته غم از دست دادن"),
    ("hundred", "fluent:hundred-points", "symbol", "perfect hundred score", "عالی صد امتیاز"),
    ("sparkles", "fluent:sparkles", "symbol", "new magic special shine", "جدید جادو ویژه درخشش"),
    ("star", "fluent:glowing-star", "symbol", "star rating best quality", "ستاره امتیاز بهترین کیفیت"),
    ("infinity", "fluent:infinity", "symbol", "infinite unlimited forever", "بی‌نهایت نامحدود همیشه"),
    ("recycle", "fluent:recycling-symbol", "symbol", "recycle repeat cycle reuse", "بازیافت تکرار چرخه"),
    ("repeat", "fluent:repeat-button", "symbol", "repeat again loop routine", "تکرار دوباره چرخه روزمره"),
    # --- technology
    ("phone", "fluent:mobile-phone", "object", "phone mobile smartphone app cellphone", "گوشی موبایل تلفن همراه اپلیکیشن برنامه"),
    ("phone-arrow", "fluent:mobile-phone-with-arrow", "object", "phone call send transfer app", "گوشی تماس ارسال انتقال"),
    ("laptop", "fluent:laptop", "object", "laptop computer work online", "لپ‌تاپ رایانه کامپیوتر کار آنلاین"),
    ("desktop", "fluent:desktop-computer", "object", "computer desktop pc screen", "کامپیوتر رایانه رومیزی صفحه نمایش"),
    ("keyboard", "fluent:keyboard", "object", "keyboard typing", "کیبورد صفحه کلید تایپ"),
    ("printer", "fluent:printer", "object", "printer print office", "چاپگر چاپ اداره"),
    ("robot", "fluent:robot", "object", "robot ai artificial intelligence automation bot machine", "ربات هوش مصنوعی خودکارسازی ماشین"),
    ("satellite", "fluent:satellite", "object", "satellite space signal communication", "ماهواره فضا سیگنال ارتباطات"),
    ("antenna", "fluent:satellite-antenna", "object", "antenna signal broadcast network", "آنتن سیگنال پخش شبکه"),
    ("television", "fluent:television", "object", "television tv broadcast media show", "تلویزیون پخش رسانه برنامه"),
    ("radio", "fluent:radio", "object", "radio broadcast news", "رادیو پخش خبر"),
    ("camera", "fluent:camera", "object", "camera photo picture", "دوربین عکس تصویر"),
    ("video-camera", "fluent:movie-camera", "object", "video camera film movie record", "دوربین فیلمبرداری فیلم ضبط"),
    ("microphone", "fluent:studio-microphone", "object", "microphone podcast voice record interview", "میکروفون پادکست صدا ضبط مصاحبه"),
    ("headphone", "fluent:headphone", "object", "headphones listen music audio", "هدفون گوش دادن موسیقی صدا"),
    ("disk", "fluent:floppy-disk", "object", "save storage data disk", "ذخیره حافظه داده"),
    ("dvd", "fluent:optical-disk", "object", "disk cd data storage", "دیسک داده"),
    ("mouse", "fluent:computer-mouse", "object", "mouse click computer", "ماوس کلیک"),
    ("joystick", "fluent:joystick", "object", "game gaming control", "بازی گیم کنترل"),
    ("telephone", "fluent:telephone", "object", "telephone call support landline", "تلفن تماس پشتیبانی"),
    ("watch", "fluent:watch", "object", "smartwatch watch wearable time", "ساعت هوشمند ساعت مچی"),
    ("microscope", "fluent:microscope", "object", "microscope science research lab", "میکروسکوپ علم تحقیق آزمایشگاه"),
    ("test-tube", "fluent:test-tube", "object", "test tube experiment lab chemistry", "لوله آزمایش آزمایش شیمی آزمایشگاه"),
    ("dna", "fluent:dna", "symbol", "dna gene genetics biology", "دی‌ان‌ای ژن ژنتیک زیست"),
    ("atom", "fluent:atom-symbol", "symbol", "atom science physics nuclear", "اتم علم فیزیک هسته‌ای"),
    ("telescope", "fluent:telescope", "object", "telescope future vision explore", "تلسکوپ آینده چشم‌انداز کاوش"),
    ("wireless", "fluent:wireless", "symbol", "wifi wireless connection network internet", "وای‌فای بی‌سیم اتصال شبکه اینترنت"),
    ("signal", "fluent:antenna-bars", "symbol", "signal network mobile data coverage", "آنتن سیگنال شبکه دیتا پوشش"),
    # --- places
    ("bank", "fluent:bank", "place", "bank financial institution branch", "بانک موسسه مالی شعبه"),
    ("department-store", "fluent:department-store", "place", "mall department store shopping center company", "پاساژ مرکز خرید فروشگاه بزرگ شرکت"),
    ("office", "fluent:office-building", "place", "office building company headquarters corporate", "ساختمان اداری شرکت دفتر مرکزی برج"),
    ("classical-building", "fluent:classical-building", "place", "government parliament court ministry institution museum", "دولت مجلس دادگاه وزارتخانه نهاد موزه ساختمان دولتی قانون"),
    ("factory", "fluent:factory", "place", "factory industry production manufacturing", "کارخانه صنعت تولید"),
    ("house", "fluent:house", "place", "house home housing rent property real estate", "خانه منزل مسکن اجاره ملک املاک"),
    ("houses", "fluent:houses", "place", "houses neighborhood housing market", "خانه‌ها محله بازار مسکن"),
    ("hospital", "fluent:hospital", "place", "hospital clinic healthcare emergency", "بیمارستان درمانگاه درمان اورژانس"),
    ("school", "fluent:school", "place", "school education students", "مدرسه آموزش دانش‌آموز"),
    ("mosque", "fluent:mosque", "place", "mosque prayer religion", "مسجد نماز دین"),
    ("cityscape", "fluent:cityscape", "place", "city town urban skyline", "شهر شهری آسمان خراش کلان‌شهر"),
    ("construction", "fluent:building-construction", "place", "construction building project development", "ساخت و ساز ساختمان پروژه عمرانی"),
    ("stadium", "fluent:stadium", "place", "stadium sport match crowd", "ورزشگاه ورزش مسابقه جمعیت"),
    ("post-office", "fluent:post-office", "place", "post office mail", "اداره پست پست"),
    ("hotel", "fluent:hotel", "place", "hotel travel tourism", "هتل سفر گردشگری"),
    ("station", "fluent:station", "place", "station train metro transit", "ایستگاه قطار مترو"),
    ("tent", "fluent:tent", "place", "tent camp", "چادر کمپ"),
    ("mountain", "fluent:snow-capped-mountain", "nature", "mountain peak challenge climb", "کوه قله چالش صعود"),
    ("desert", "fluent:desert", "nature", "desert dry drought", "بیابان کویر خشکسالی"),
    ("volcano", "fluent:volcano", "nature", "volcano eruption crisis", "آتشفشان فوران بحران"),
    # --- transport and energy
    ("car", "fluent:automobile", "transport", "car automobile vehicle drive", "خودرو ماشین اتومبیل رانندگی"),
    ("taxi", "fluent:taxi", "transport", "taxi ride cab", "تاکسی سفر درون‌شهری"),
    ("bus", "fluent:bus", "transport", "bus public transport", "اتوبوس حمل و نقل عمومی"),
    ("truck", "fluent:delivery-truck", "transport", "truck delivery shipping logistics", "کامیون ارسال حمل بار لجستیک"),
    ("airplane", "fluent:airplane", "transport", "airplane flight travel plane airline", "هواپیما پرواز سفر هوایی"),
    ("ship", "fluent:ship", "transport", "ship cargo export import sea", "کشتی بار صادرات واردات دریا"),
    ("train", "fluent:high-speed-train", "transport", "train rail travel", "قطار راه آهن سفر"),
    ("motorcycle", "fluent:motor-scooter", "transport", "motorcycle scooter courier delivery", "موتورسیکلت پیک ارسال"),
    ("bicycle", "fluent:bicycle", "transport", "bicycle bike ride", "دوچرخه"),
    ("fuel", "fluent:fuel-pump", "object", "fuel gasoline petrol gas price", "بنزین سوخت پمپ بنزین قیمت سوخت"),
    ("oil", "fluent:oil-drum", "object", "oil barrel crude petroleum", "نفت بشکه نفت خام"),
    # --- health and body
    ("pill", "fluent:pill", "health", "pill medicine drug tablet treatment", "قرص دارو درمان"),
    ("syringe", "fluent:syringe", "health", "syringe injection vaccine shot", "سرنگ تزریق واکسن آمپول"),
    ("stethoscope", "fluent:stethoscope", "health", "stethoscope doctor checkup diagnosis", "گوشی پزشکی پزشک معاینه تشخیص"),
    ("thermometer", "fluent:thermometer", "health", "thermometer fever temperature sick", "دماسنج تب دما بیمار"),
    ("microbe", "fluent:microbe", "health", "virus microbe bacteria germ infection", "ویروس میکروب باکتری عفونت"),
    ("heart-organ", "fluent:anatomical-heart", "health", "heart cardiology blood pressure", "قلب قلبی فشار خون"),
    ("brain", "fluent:brain", "health", "brain mind memory intelligence mental", "مغز ذهن حافظه هوش روان"),
    ("lungs", "fluent:lungs", "health", "lungs breathing smoking air", "ریه تنفس سیگار هوا"),
    ("tooth", "fluent:tooth", "health", "tooth dentist dental", "دندان دندانپزشک"),
    ("bone", "fluent:bone", "health", "bone skeleton", "استخوان اسکلت"),
    ("blood", "fluent:drop-of-blood", "health", "blood drop donation test", "خون قطره اهدا آزمایش"),
    ("bandage", "fluent:adhesive-bandage", "health", "bandage injury wound", "چسب زخم آسیب زخم"),
    ("xray", "fluent:x-ray", "health", "x-ray scan radiology", "رادیولوژی عکس اشعه ایکس"),
    ("medical", "fluent:medical-symbol", "symbol", "medicine health medical care", "پزشکی سلامت درمان"),
    ("ambulance", "fluent:ambulance", "transport", "ambulance emergency", "آمبولانس اورژانس"),
    ("wheelchair", "fluent:manual-wheelchair", "health", "wheelchair disability", "ویلچر معلولیت"),
    ("soap", "fluent:soap", "health", "soap hygiene wash clean", "صابون بهداشت شستن تمیز"),
    ("cigarette", "fluent:cigarette", "health", "cigarette smoking tobacco", "سیگار سیگار کشیدن دخانیات"),
    ("apple", "fluent:red-apple", "food", "apple fruit healthy diet", "سیب میوه سالم رژیم"),
    ("salad", "fluent:green-salad", "food", "salad vegetables healthy food", "سالاد سبزیجات غذای سالم"),
    ("bread", "fluent:bread", "food", "bread food bakery", "نان غذا نانوایی"),
    ("rice", "fluent:cooked-rice", "food", "rice food meal", "برنج غذا"),
    ("water", "fluent:droplet", "nature", "water drop", "آب قطره"),
    ("glass-milk", "fluent:glass-of-milk", "food", "milk dairy drink", "شیر لبنیات نوشیدنی"),
    ("tea", "fluent:teacup-without-handle", "food", "tea drink", "چای نوشیدنی"),
    ("coffee", "fluent:hot-beverage", "food", "coffee hot drink break", "قهوه نوشیدنی گرم استراحت"),
    ("hamburger", "fluent:hamburger", "food", "fast food burger junk food", "فست فود همبرگر"),
    ("running-shoe", "fluent:running-shoe", "object", "running exercise sport fitness", "دویدن ورزش تناسب اندام"),
    ("bed", "fluent:bed", "object", "bed sleep rest", "تخت خواب استراحت"),
    # --- nature and weather
    ("sun", "fluent:sun", "nature", "sun sunny bright summer", "خورشید آفتاب روشن تابستان"),
    ("cloud", "fluent:cloud", "nature", "cloud cloudy storage", "ابر ابری"),
    ("rain", "fluent:cloud-with-rain", "nature", "rain weather", "باران هوا"),
    ("storm", "fluent:cloud-with-lightning-and-rain", "nature", "storm crisis turbulence", "طوفان بحران آشوب"),
    ("snowflake", "fluent:snowflake", "nature", "snow cold winter freeze", "برف سرما زمستان یخ"),
    ("tornado", "fluent:tornado", "nature", "tornado chaos disaster", "گردباد هرج و مرج فاجعه"),
    ("tree", "fluent:deciduous-tree", "nature", "tree nature growth environment", "درخت طبیعت رشد محیط زیست"),
    ("seedling", "fluent:seedling", "nature", "seedling plant start grow invest small", "نهال گیاه شروع رشد سرمایه‌گذاری"),
    ("potted-plant", "fluent:potted-plant", "nature", "plant growth care", "گلدان گیاه رشد"),
    ("wilted", "fluent:wilted-flower", "nature", "wilted decline sad", "پژمرده افول غم"),
    ("rainbow", "fluent:rainbow", "nature", "rainbow hope variety", "رنگین کمان امید تنوع"),
    ("moon", "fluent:crescent-moon", "nature", "moon night", "ماه شب"),
    ("ice", "fluent:ice", "nature", "ice frozen freeze", "یخ منجمد"),
    ("snail", "fluent:snail", "nature", "snail slow speed", "حلزون کند آهسته"),
    ("turtle", "fluent:turtle", "nature", "turtle slow steady", "لاک‌پشت کند"),
    ("rabbit", "fluent:rabbit", "nature", "rabbit fast quick", "خرگوش سریع"),
    ("owl", "fluent:owl", "nature", "owl wisdom wise night", "جغد خرد دانا"),
    ("bee", "fluent:honeybee", "nature", "bee work busy team", "زنبور کار پرکار"),
    ("ant", "fluent:ant", "nature", "ant small work team", "مورچه کوچک کار تیم"),
    ("lion", "fluent:lion", "nature", "lion strong brave leader", "شیر قوی شجاع رهبر"),
    ("fox", "fluent:fox", "nature", "fox clever trick", "روباه زیرک حیله"),
    ("snake", "fluent:snake", "nature", "snake danger fraud", "مار خطر فریب"),
    ("shark", "fluent:shark", "nature", "shark predator risk big player", "کوسه شکارچی ریسک"),
    ("bull", "fluent:ox", "nature", "bull bullish market", "گاو بازار صعودی"),
    ("bear", "fluent:bear", "nature", "bear bearish market", "خرس بازار نزولی"),
    # --- people as single props (crowds use Open Peeps)
    ("detective", "fluent:detective", "object", "detective investigation inspector", "کارآگاه تحقیق بازرس"),
    ("police", "fluent:police-officer", "object", "police officer law enforcement", "پلیس مامور"),
    ("teacher", "fluent:teacher", "object", "teacher class lesson", "معلم کلاس درس"),
    ("scientist", "fluent:scientist", "object", "scientist researcher", "دانشمند پژوهشگر"),
    ("technologist", "fluent:technologist", "object", "programmer developer coder engineer", "برنامه‌نویس توسعه‌دهنده مهندس"),
    ("busts", "fluent:busts-in-silhouette", "symbol", "users people audience customers", "کاربران مردم مخاطبان مشتریان"),
    # --- health icons (line style)
    ("health-chart", "health:chart-line", "symbol", "statistics trend line chart", "آمار روند نمودار خطی"),
    ("health-coins", "health:coins", "object", "coins cost money", "سکه‌ها هزینه پول"),
    ("health-bills", "health:bills", "object", "bills money payment cost", "صورتحساب پول پرداخت هزینه"),
    ("health-ai", "health:artificial-intelligence", "symbol", "artificial intelligence ai brain chip", "هوش مصنوعی تراشه"),
    ("health-city", "health:city", "place", "city buildings", "شهر ساختمان‌ها"),
    ("health-cardiogram", "health:cardiogram", "health", "cardiogram heart rate pulse", "نوار قلب ضربان نبض"),
    ("health-bacteria", "health:bacteria", "health", "bacteria infection", "باکتری عفونت"),
    ("health-blood-pressure", "health:blood-pressure-monitor", "health", "blood pressure monitor", "فشارسنج فشار خون"),
]

# Drawn for Trimio (no third-party content): things the open sets lack, in the same flat style, 64 units square.
CUSTOM = {
    "safe": """<g><path fill="#8a9097" d="M8 7h48a3 3 0 0 1 3 3v45a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V10a3 3 0 0 1 3-3z"/>
<path fill="#6f757c" d="M53 7h3a3 3 0 0 1 3 3v45a3 3 0 0 1-3 3h-3z"/>
<path fill="#c4c9ce" d="M11 11h38a2 2 0 0 1 2 2v39a2 2 0 0 1-2 2H11a2 2 0 0 1-2-2V13a2 2 0 0 1 2-2z"/>
<path fill="#b0b6bc" d="M15 22h30a1.5 1.5 0 0 1 1.5 1.5v24a1.5 1.5 0 0 1-1.5 1.5H15a1.5 1.5 0 0 1-1.5-1.5v-24A1.5 1.5 0 0 1 15 22z"/>
<path fill="#dfe2e5" d="M17 13.5h26a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H17a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z"/>
<circle cx="27" cy="35.5" r="8" fill="#e6e8ea"/><circle cx="27" cy="35.5" r="5.5" fill="#8a9097"/><circle cx="27" cy="35.5" r="2.2" fill="#4f545a"/>
<path fill="#4f545a" d="M26.4 26.2h1.2v2.4h-1.2zM26.4 42.4h1.2v2.4h-1.2zM17.7 34.9h2.4v1.2h-2.4zM33.9 34.9h2.4v1.2h-2.4z"/>
<path fill="#5d6369" d="M40 28h3.4a1.7 1.7 0 0 1 1.7 1.7v11.6a1.7 1.7 0 0 1-1.7 1.7H40a1.7 1.7 0 0 1-1.7-1.7V29.7A1.7 1.7 0 0 1 40 28z"/>
<path fill="#5d6369" d="M6.5 17h3.5v7H6.5zM6.5 41h3.5v7H6.5z"/>
<path fill="#4a4f54" d="M10 58h9v4h-9zM45 58h9v4h-9z"/></g>""",
    "gold-bars": """<g><path fill="#c98a00" d="M6 50l5-13h20l5 13z"/><path fill="#ffd54a" d="M11 37h20l-2.5 4h-15z"/><path fill="#f4b400" d="M8.5 50l5-9h15l5 9z"/>
<path fill="#c98a00" d="M30 50l5-13h20l5 13z"/><path fill="#ffd54a" d="M35 37h20l-2.5 4h-15z"/><path fill="#f4b400" d="M32.5 50l5-9h15l5 9z"/>
<path fill="#c98a00" d="M18 37l5-13h20l5 13z"/><path fill="#ffd54a" d="M23 24h20l-2.5 4h-15z"/><path fill="#f4b400" d="M20.5 37l5-9h15l5 9z"/>
<path fill="#fff1b3" d="M26 30h3l-2 5h-3zM14 43h3l-2 5h-3zM38 43h3l-2 5h-3z"/></g>""",
    "storefront": """<g><path fill="#e9e5dd" d="M5 12h54v48H5z"/><path fill="#d3cdc1" d="M3 9h58v4H3z"/>
<path fill="#f4f1ea" d="M8 15h48v10H8z"/>
<path fill="#bfb6a6" d="M5 27h54v3H5z"/>
<path fill="#c9c1b3" d="M5 30h6l-1 5a2.5 2.5 0 0 1-5 0zM17 30h6l-1 5a2.5 2.5 0 0 1-5 0zM29 30h6l-1 5a2.5 2.5 0 0 1-5 0zM41 30h6l-1 5a2.5 2.5 0 0 1-5 0zM53 30h6l-1 5a2.5 2.5 0 0 1-5 0z"/>
<path fill="#f2eee6" d="M11 30h6l-1 5a2.5 2.5 0 0 1-5 0zM23 30h6l-1 5a2.5 2.5 0 0 1-5 0zM35 30h6l-1 5a2.5 2.5 0 0 1-5 0zM47 30h6l-1 5a2.5 2.5 0 0 1-5 0z"/>
<path fill="#b8d0dc" d="M9 39h20v17H9zM43 39h12v17H43z"/><path fill="#d8e7ee" d="M12 41l5 0-6 9v-5zM46 41h4l-5 8v-5z"/>
<path fill="#8f9aa6" d="M32 39h9v19h-9z"/><path fill="#5f6873" d="M38.5 48h1.2v3h-1.2z"/>
<path fill="#a49b8d" d="M3 58h58v3H3z"/></g>""",
}


NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
ARGS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


def path_tokens(d: str):
    """Commands and numbers of SVG path data; arc flags (written as single digits, often run together) kept apart."""
    out, i, cmd, arg = [], 0, "", 0
    while i < len(d):
        ch = d[i]
        if ch.isalpha() and ch not in "eE":
            out.append(ch)
            cmd, arg, i = ch.upper(), 0, i + 1
        elif ch in "-+.0123456789":
            if cmd == "A" and arg % 7 in (3, 4) and ch in "01":
                out.append(float(ch))
                arg, i = arg + 1, i + 1
                continue
            m = NUMBER.match(d, i)
            out.append(float(m.group(0)))
            arg, i = arg + 1, m.end()
        else:
            i += 1
    return out


def fmt(v: float, digits: int) -> str:
    s = ("%.*f" % (digits, v)).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "", "-") else s


def round_path(d: str, digits: int) -> str:
    """Path data re-written with [digits] decimals and explicit separators (no number can merge with the next)."""
    out = []
    prev_num = False
    for t in path_tokens(d):
        if isinstance(t, str):
            out.append(t)
            prev_num = False
        else:
            n = fmt(t, digits)
            if prev_num and not n.startswith("-"):
                out.append(" ")
            out.append(n)
            prev_num = True
    return "".join(out)


def round_numbers(svg: str, digits: int = 1) -> str:
    return re.sub(r'\bd="([^"]*)"', lambda m: 'd="%s"' % round_path(m.group(1), digits), svg)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--peeps", required=True)
    ap.add_argument("--fluent", required=True)
    ap.add_argument("--health", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    peeps = json.load(open(a.peeps))
    people = {k: round_numbers(v, 1) for k, v in sorted(peeps.items()) if "<undefined" not in v}
    with open(f"{a.out}/people.json", "w") as f:
        json.dump({"v": 1, "source": "open-peeps", "licence": "CC0-1.0", "pieces": people}, f, ensure_ascii=False, separators=(",", ":"))

    sets = {"fluent": json.load(open(a.fluent)), "health": json.load(open(a.health)),
            "trimio": {"width": 64, "height": 64, "icons": {k: {"body": v.replace("\n", "")} for k, v in CUSTOM.items()}}}
    items, missing = [], []
    for oid, src, kind, en, fa in OBJECTS:
        lib, name = src.split(":")
        d = sets[lib]
        icon = d["icons"].get(name)
        if icon is None:
            missing.append(src)
            continue
        w, h = icon.get("width", d.get("width", 24)), icon.get("height", d.get("height", 24))
        items.append({"id": oid, "src": src, "kind": kind, "en": en.split(), "fa": fa.split(), "w": w, "h": h, "svg": round_numbers(icon["body"], 2 if max(w, h) <= 64 else 1)})
    sources = {
        "fluent": {"title": "Fluent Emoji (flat)", "author": "Microsoft", "licence": "MIT", "home": "https://github.com/microsoft/fluentui-emoji"},
        "trimio": {"title": "Trimio drawings", "author": "Trimio", "licence": "LicenseRef-Trimio-Own", "home": ""},
        "health": {"title": "Health Icons", "author": "Resolve to Save Lives", "licence": "MIT", "home": "https://github.com/resolvetosavelives/healthicons"},
    }
    with open(f"{a.out}/objects.json", "w") as f:
        json.dump({"v": 1, "sources": sources, "items": items}, f, ensure_ascii=False, separators=(",", ":"))
    print(len(people), "people pieces;", len(items), "objects; missing:", missing)


if __name__ == "__main__":
    main()
