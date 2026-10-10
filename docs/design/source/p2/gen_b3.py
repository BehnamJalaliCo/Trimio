import sys
S=sys.argv[1]
def wave(n, hi=24, color='rgba(242,237,228,.38)', mark=None):
    import math
    out=[]
    for i in range(n):
        h=3+abs(math.sin(i*.6)*math.cos(i*.21))*hi
        c=mark if (mark and 22<=i<=33) else color
        out.append(f'<div style="flex:1;border-radius:1px;height:{h:.0f}px;background:{c}"></div>')
    return ''.join(out)

def frame(active, panel, caption=True, overlay=''):
    tools=[('text','متن'),('style','سبک'),('music','صدا'),('spark','المان'),('cut','برش')]
    tb=''.join(f'<div style="display:flex;flex-direction:column;align-items:center;gap:3px;color:{"var(--acid)" if k==active else "rgba(242,237,228,.6)"}"><i data-i="{k}" data-s="21"></i><span style="font:600 9px \'UI\'">{l}</span></div>' for k,l in tools)
    cap = '''
  <div style="position:absolute; top:118px; right:26px; text-align:right; z-index:2; color:var(--media)">
    <div class="lab" style="color:var(--acid)">BTC / USDT</div>
    <div style="font:900 52px/1 'ACC'; direction:ltr; text-align:right; margin-top:4px">+5.2<span style="color:var(--em)">%</span></div>
  </div>
  <div style="position:absolute; top:236px; right:26px; text-align:right; z-index:2; color:var(--media)">
    <div style="font:1000 42px/1 'DSP'">پنج</div>
    <div style="display:inline-block; font:1000 40px/.9 'DSP'; background:var(--acid); color:var(--onacid); padding:18px 8px 4px; border-radius:5px; margin-top:6px">درصد</div>
  </div>''' if caption else ''
    return f'''
<div class="phone grain">
  <div class="shot" style="position:absolute; inset:0"><div class="key"></div><div class="bok" style="width:90px;height:90px;top:120px;left:20px"></div><div class="sub" style="height:56%"></div></div>
  <div style="position:absolute; inset:0; background:linear-gradient(180deg, rgba(0,0,0,.4), rgba(0,0,0,0) 18%, rgba(0,0,0,0) 40%, rgba(0,0,0,.7) 60%)"></div>
  {cap}
  {overlay}
  <div class="top"></div>
  <div class="topbar" style="position:relative; z-index:3">
    <div class="rbtn glass media"><i data-i="back" data-s="20"></i></div>
    <div class="row" style="gap:8px">
      <div class="rbtn glass media"><i data-i="undo" data-s="19"></i></div>
      <div style="height:42px;border-radius:21px;padding:0 18px;display:flex;align-items:center;gap:8px;font:800 14px 'UI';background:var(--em);color:var(--onem)"><i data-i="export" data-s="18"></i>خروجی</div>
    </div>
  </div>
  <div class="glass media" style="position:absolute; left:12px; top:116px; width:52px; padding:12px 0; border-radius:26px; display:flex; flex-direction:column; align-items:center; gap:14px; z-index:3">{tb}</div>
  <div class="glass media" style="position:absolute; left:10px; right:10px; bottom:18px; padding:14px; border-radius:26px; z-index:3">
    {panel}
  </div>
</div>'''

timeline_mini = f'''
    <div style="position:relative; height:70px; margin-top:12px; border-radius:14px; background:rgba(0,0,0,.32); overflow:hidden">
      <div style="position:absolute; top:8px; left:10px; right:10px; height:14px; display:flex; gap:2px">
        <div style="flex:2;border-radius:3px;background:rgba(242,237,228,.14)"></div><div style="flex:3;border-radius:3px;background:rgba(242,237,228,.22)"></div><div style="flex:3;border-radius:3px;background:var(--acid)"></div><div style="flex:2;border-radius:3px;background:rgba(242,237,228,.14)"></div><div style="flex:2;border-radius:3px;background:rgba(242,237,228,.14)"></div>
      </div>
      <div style="position:absolute; top:28px; left:10px; right:10px; height:34px; display:flex; gap:2px; align-items:center; direction:ltr">{wave(66)}</div>
      <div style="position:absolute; top:0; bottom:0; right:44%; width:2px; background:#fff; box-shadow:0 0 10px rgba(255,255,255,.8)"></div>
    </div>'''

def transport(right='بازنویسی با کارگردان'):
    return f'''
    <div class="row sb" style="margin-top:10px">
      <span style="font:600 11.5px 'ACC'; color:rgba(242,237,228,.6); direction:ltr">00:12 / 00:42</span>
      <div style="width:42px;height:42px;border-radius:21px;background:var(--media);color:#0B0A09;display:grid;place-items:center"><i data-i="play" data-s="20"></i></div>
      <span class="row" style="gap:6px; font:600 11.5px 'UI'; color:rgba(242,237,228,.6); min-width:80px; justify-content:flex-start">{('<span class="dd" style="width:10px;height:10px"></span>' + right) if right else ''}</span>
    </div>'''

# TEXT
p_text = f'''
    <div class="row sb"><span style="font:800 13px 'UI'">«پنج درصد»</span><span class="lab" style="color:rgba(242,237,228,.5)">00:11.8 → 00:12.6</span></div>
    <div style="display:flex; flex-wrap:wrap; gap:6px; font:600 13.5px 'UI'; margin-top:10px">
      <span style="padding:5px 9px;border-radius:10px;background:rgba(255,255,255,.06)">امروز</span>
      <span style="padding:5px 9px;border-radius:10px;background:rgba(255,255,255,.12);font-weight:800">بیت‌کوین</span>
      <span style="padding:5px 9px;border-radius:10px;background:var(--acid);color:#0B0A09;font-weight:900;box-shadow:0 0 0 2px #fff">پنج درصد</span>
      <span style="padding:5px 9px;border-radius:10px;background:rgba(255,255,255,.06)">رشد کرد</span>
      <span style="padding:5px 9px;border-radius:10px;opacity:.35;text-decoration:line-through">و خب</span>
      <span style="padding:5px 9px;border-radius:10px;background:rgba(255,255,255,.06)">سیگنال</span>
    </div>
    <div style="display:grid; grid-template-columns:repeat(4,1fr); gap:6px; margin-top:12px">
      <div style="height:54px;border-radius:12px;background:var(--acid);color:#0B0A09;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font:700 10.5px 'UI'"><i data-i="star" data-s="18"></i>تأکید</div>
      <div style="height:54px;border-radius:12px;background:rgba(255,255,255,.08);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font:700 10.5px 'UI'"><i data-i="text" data-s="18"></i>ویرایش</div>
      <div style="height:54px;border-radius:12px;background:rgba(255,255,255,.08);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font:700 10.5px 'UI'"><i data-i="bolt" data-s="18"></i>ضربه</div>
      <div style="height:54px;border-radius:12px;background:rgba(255,255,255,.08);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font:700 10.5px 'UI'"><i data-i="cut" data-s="18"></i>حذف</div>
    </div>
    {timeline_mini}{transport()}'''

# STYLE
def stile(bg, name, inner, sel=False, fg='#F2EDE4'):
    ring='box-shadow:0 0 0 2px var(--em);' if sel else ''
    return f'<div style="flex:none;width:86px;height:110px;border-radius:14px;overflow:hidden;position:relative;background:{bg};{ring}">{inner}<div style="position:absolute;bottom:7px;right:8px;font:700 10.5px \'UI\';color:{fg}">{name}</div></div>'
p_style = f'''
    <div class="row sb"><span style="font:800 14px 'UI'">سبک</span><span class="row" style="gap:6px; font:600 11.5px 'UI'; color:rgba(242,237,228,.6)"><span class="dd" style="width:10px;height:10px"></span>کارگردان: نوآر بهترین است</span></div>
    <div style="display:flex; gap:8px; margin-top:12px; overflow:hidden">
      {stile('#0d0c0b','نوآر','<div style="position:absolute;top:22px;right:8px;font:1000 20px/1 DSP;color:#F2EDE4">نوآر</div><div style="position:absolute;top:48px;right:8px;height:2px;width:20px;background:#FF5B2E"></div>',True)}
      {stile('#EFEDE6','کینتیک','<div style="position:absolute;top:20px;right:8px;font:1000 22px/1 DSP;color:#111">بزن</div><div style="position:absolute;top:48px;right:8px;height:7px;width:32px;background:#D7FF3A"></div>',fg='#111')}
      {stile('radial-gradient(80% 70% at 50% 25%, #2b3d63, #070b14)','لیکوئید','<div style="position:absolute;inset:20px 10px auto;height:40px;border-radius:10px;background:linear-gradient(160deg,rgba(255,255,255,.2),rgba(255,255,255,.05));box-shadow:inset 0 1px 0 rgba(255,255,255,.35)"></div>')}
      {stile('#FFE14D','نئوبروتال','<div style="position:absolute;top:22px;right:8px;padding:2px 6px;background:#fff;border:2px solid #111;box-shadow:3px 3px 0 #111;font:900 13px EXP;color:#111">سود!</div>',fg='#111')}
    </div>
    <div style="margin-top:14px">
      <div class="row sb"><span style="font:600 12px 'UI'; color:rgba(242,237,228,.7)">انرژی حرکت</span><span style="font:700 12px 'ACC'; direction:ltr">80</span></div>
      <div style="height:4px;border-radius:2px;background:rgba(255,255,255,.12);margin-top:8px;position:relative"><div style="position:absolute;right:0;top:0;bottom:0;width:80%;background:var(--em);border-radius:2px"></div><div style="position:absolute;right:calc(80% - 9px);top:-7px;width:18px;height:18px;border-radius:9px;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.5)"></div></div>
    </div>
    <div style="margin-top:16px">
      <div class="row sb"><span style="font:600 12px 'UI'; color:rgba(242,237,228,.7)">چگالی زیرنویس</span></div>
      <div class="seg" style="margin-top:8px; background:rgba(255,255,255,.07)"><div style="color:rgba(242,237,228,.6)">کلمه‌ای</div><div class="on" style="background:var(--media);color:#0B0A09">عبارتی</div><div style="color:rgba(242,237,228,.6)">جمله‌ای</div></div>
    </div>
    {transport('مقایسهٔ قبل و بعد')}'''

# SOUND
p_sound = f'''
    <div class="row sb"><span style="font:800 14px 'UI'">صدا</span><span style="font:700 11.5px 'ACC'; color:var(--acid); direction:ltr">-14 LUFS ✓</span></div>
    <div style="margin-top:12px; display:flex; flex-direction:column; gap:8px">
      <div class="row" style="gap:10px; padding:10px; border-radius:14px; background:rgba(255,255,255,.08); box-shadow:inset 0 0 0 1.5px var(--em)">
        <div style="width:38px;height:38px;border-radius:10px;background:linear-gradient(135deg,#FF5B2E,#7a2a10);display:grid;place-items:center"><i data-i="music" data-s="18"></i></div>
        <div style="flex:1"><div style="font:800 13px 'UI'">پالس شب</div><div style="font:500 10.5px 'UI'; color:rgba(242,237,228,.6)">پرانرژی · ۱۲۲ BPM · هم‌گام با کات‌ها</div></div>
        <span class="chip" style="height:24px;background:rgba(215,255,58,.12);color:var(--acid)">کارگردان</span>
      </div>
      <div class="row" style="gap:10px; padding:10px; border-radius:14px; background:rgba(255,255,255,.05)">
        <div style="width:38px;height:38px;border-radius:10px;background:linear-gradient(135deg,#7FB2FF,#1d2b55);display:grid;place-items:center"><i data-i="music" data-s="18"></i></div>
        <div style="flex:1"><div style="font:800 13px 'UI'">مه صبح</div><div style="font:500 10.5px 'UI'; color:rgba(242,237,228,.6)">آرام · ۸۴ BPM</div></div>
      </div>
    </div>
    <div style="margin-top:14px">
      <div class="row sb"><span style="font:600 12px 'UI'; color:rgba(242,237,228,.7)">موسیقی زیر صدا</span><span style="font:700 12px 'ACC'; direction:ltr">-18 dB</span></div>
      <div style="height:4px;border-radius:2px;background:rgba(255,255,255,.12);margin-top:8px;position:relative"><div style="position:absolute;right:0;top:0;bottom:0;width:35%;background:var(--em);border-radius:2px"></div><div style="position:absolute;right:calc(35% - 9px);top:-7px;width:18px;height:18px;border-radius:9px;background:#fff"></div></div>
    </div>
    <div class="row sb" style="margin-top:14px"><span style="font:600 12px 'UI'; color:rgba(242,237,228,.7)">افکت روی تأکیدها</span><span class="row" style="gap:6px"><span class="chip" style="height:24px;background:rgba(255,255,255,.1)">۱۲ افکت</span><div class="tog on"></div></span></div>
    <div style="position:relative; height:44px; margin-top:12px; border-radius:12px; background:rgba(0,0,0,.32); overflow:hidden">
      <div style="position:absolute; inset:6px 10px; display:flex; gap:2px; align-items:center; direction:ltr">{wave(66, 26, 'rgba(242,237,228,.35)', '#FF5B2E')}</div>
    </div>
    {transport('میکس خودکار')}'''

# ELEMENTS
def el(icon, title, sub, tc, on=True):
    return f'''<div class="row" style="gap:10px; padding:10px 0; border-bottom:1px solid rgba(255,255,255,.08)">
        <div style="width:36px;height:36px;border-radius:10px;background:{tc};display:grid;place-items:center;color:#0B0A09">{icon}</div>
        <div style="flex:1"><div style="font:800 13px 'UI'">{title}</div><div style="font:500 10.5px 'UI'; color:rgba(242,237,228,.6)">{sub}</div></div>
        <div class="tog {'on' if on else ''}"></div></div>'''
p_el = f'''
    <div class="row sb"><span style="font:800 14px 'UI'">المان‌ها</span><span class="row" style="gap:6px; font:600 11.5px 'UI'; color:rgba(242,237,228,.6)"><span class="dd" style="width:10px;height:10px"></span>۵ پیشنهاد کارگردان</span></div>
    <div style="margin-top:6px">
      {el('<span style="font:900 13px ACC">%</span>','شمارندهٔ ۵٪','۰۰:۱۱ · روی «پنج درصد»','#D7FF3A')}
      {el('<span style="font:900 14px ACC">₿</span>','آیکون بیت‌کوین','۰۰:۰۴ · ورود با ضربه','#FFB547')}
      {el('<i data-i="wave" data-s="18"></i>','تیکر قیمت','۰۰:۱۸ تا ۰۰:۲۶ · پایین قاب','#F2EDE4')}
      {el('<i data-i="bolt" data-s="18"></i>','فلش صعودی','۰۰:۲۹ · روی «تارگت»','#FF5B2E', False)}
    </div>
    <div class="row" style="gap:8px; margin-top:12px">
      <div style="flex:1;height:44px;border-radius:14px;background:rgba(255,255,255,.08);display:flex;align-items:center;justify-content:center;gap:8px;font:700 13px 'UI'"><i data-i="plus" data-s="18"></i>افزودن المان</div>
      <div style="flex:1;height:44px;border-radius:14px;background:rgba(255,255,255,.08);display:flex;align-items:center;justify-content:center;gap:8px;font:700 13px 'UI'"><span class="dd" style="width:12px;height:12px"></span>پیشنهاد بیشتر</div>
    </div>
    {transport('')}'''

# CUT
cuts_tl = f'''
    <div style="position:relative; height:76px; margin-top:12px; border-radius:14px; background:rgba(0,0,0,.32); overflow:hidden">
      <div style="position:absolute; inset:8px 10px; display:flex; gap:2px; align-items:center; direction:ltr">{wave(66, 30)}</div>
      <div style="position:absolute; top:0; bottom:0; left:18%; width:9%; background:repeating-linear-gradient(135deg, rgba(255,91,46,.55) 0 4px, rgba(255,91,46,.15) 4px 9px); border-left:1.5px solid var(--em); border-right:1.5px solid var(--em)"></div>
      <div style="position:absolute; top:0; bottom:0; left:46%; width:6%; background:repeating-linear-gradient(135deg, rgba(255,91,46,.55) 0 4px, rgba(255,91,46,.15) 4px 9px); border-left:1.5px solid var(--em); border-right:1.5px solid var(--em)"></div>
      <div style="position:absolute; top:0; bottom:0; left:71%; width:11%; background:repeating-linear-gradient(135deg, rgba(255,91,46,.55) 0 4px, rgba(255,91,46,.15) 4px 9px); border-left:1.5px solid var(--em); border-right:1.5px solid var(--em)"></div>
    </div>'''
p_cut = f'''
    <div class="row sb"><span style="font:800 14px 'UI'">برش</span><span style="font:700 12px 'ACC'; direction:ltr; color:rgba(242,237,228,.7)">00:42 → <span style="color:var(--acid)">00:36</span></span></div>
    {cuts_tl}
    <div style="margin-top:8px">
      <div class="row" style="gap:10px; padding:10px 0; border-bottom:1px solid rgba(255,255,255,.08)"><div style="flex:1"><div style="font:800 13px 'UI'">حذف سکوت‌ها</div><div style="font:500 10.5px 'UI'; color:rgba(242,237,228,.6)">۷ مکث بالای ۴۰۰ میلی‌ثانیه · ۴٫۱ ثانیه</div></div><div class="tog on"></div></div>
      <div class="row" style="gap:10px; padding:10px 0; border-bottom:1px solid rgba(255,255,255,.08)"><div style="flex:1"><div style="font:800 13px 'UI'">حذف تکیه‌کلام‌ها</div><div style="font:500 10.5px 'UI'; color:rgba(242,237,228,.6)">«خب»، «یعنی»، «اممم» · ۵ مورد</div></div><div class="tog on"></div></div>
      <div class="row" style="gap:10px; padding:10px 0"><div style="flex:1"><div style="font:800 13px 'UI'">کات روی ضرب موسیقی</div><div style="font:500 10.5px 'UI'; color:rgba(242,237,228,.6)">جابه‌جایی کات‌ها تا ۸۰ میلی‌ثانیه</div></div><div class="tog"></div></div>
    </div>
    {transport('')}'''

phones = frame('text', p_text) + frame('style', p_style) + frame('music', p_sound) + frame('spark', p_el) + frame('cut', p_cut)
html='<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="base.css"><link rel="stylesheet" href="sig.css"></head><body><div class="board">'+phones+'</div><script src="icons.js"></script></body></html>'
open(S+'/b3.html','w',encoding='utf-8').write(html)
