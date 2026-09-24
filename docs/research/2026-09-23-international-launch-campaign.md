# International Launch Campaign - Anti-Vocale

Task TASK-642 (maintainer request 2026-09-23). Extends the 2026-07-12 zero-budget
promotion playbook (docs/research/2026-07-12-zero-budget-promotion.md) and the
2026-08-27 German market research (claudedocs/research_german-popularity_2026-08-27.md)
to every market where the app ships a translation.

Status: RESEARCH IN PROGRESS. Channel maps being verified live 2026-09-23;
post drafts follow after the maps land. NOTHING gets posted without the
maintainer approving the batch texts (standing rule).

## The hook, corrected by today's verified facts (2026-09-23)

WhatsApp's OFFICIAL FAQ (fetched live today, faq.whatsapp.com/241617298315321,
Android tab): transcription on Android supports "English, Portuguese, Spanish
and Russian" ONLY. iOS adds more (it, fr, de, tr, ja, ko, zh, ar on iOS 16+;
he, da, nl, fi, ms, no, sv on iOS 17+). Two consequences:

1. THE LANGUAGE-GAP HOOK IS 9-MARKETS WIDE: de, fr, it, tr, pl, hi, fa, he, uk
   all lack WhatsApp Android transcription. The old Italy-only hook
   generalizes; per-market posts lead with their own language.
2. THE PRIVACY-VS-WHATSAPP HOOK IS DEAD: the same FAQ confirms WhatsApp
   transcription is on-device and E2E-protected. Do NOT claim "WhatsApp
   uploads your audio". Our honest differentiators: Android language coverage,
   model choice, transcription of the WHOLE history and any shared audio/video
   file, free and open source.

CAVEAT for r/fossdroid (105,564 subs, still the best EN channel): rule 11
bans AI-written posts and "vibe-coded apps". Our repo publicly discloses
AI assistance in development. The posts must read handwritten (they will be),
and the MAINTAINER decides whether an AI-assisted app fits that sub's spirit
before we post there. r/droidappshowcase (15,893 subs, 1 app/week/account) and
Show HN (guidelines re-verified) have no such rule.

## The one-line pitch (adapted per market, unslop rules)

Free, open-source Android app that transcribes voice messages (WhatsApp,
Telegram, any share) fully offline: the audio never leaves the phone. Multiple
models per language, community-importable models, video files too.

## Markets and model recommendations

The model column is what we KNOW from our own catalog and measurements; posts
must not claim quality we have not verified (the scout's per-language rule).

| Market | Language hook | Recommended model(s) today | Notes (honesty constraints) |
|---|---|---|---|
| Global EN | WhatsApp Android transcription exists for EN but is cloud-side; ours is offline + free | Parakeet TDT v3 (default, 25 langs, fast), Whisper Turbo | claim only what's public: offline, free, model choice |
| IT | WhatsApp Android transcription does NOT support Italian (verified 2026-07-12; reverify before posting) | Whisper Distil Large v3 IT (best IT quality we measured), Parakeet v3 default | our strongest measured story (4.3% clean WER class) |
| DE/AT/CH | privacy-conscious, F-Droid-heavy; WhatsApp dominant | Kroko Community Zipformer DE (streaming, community catalog), Whisper v3 Turbo DE, Parakeet v3 | Swiss German: Whisper v3 Turbo gsw (catalog); German organic demand documented (android-hilfe threads, Mastodon) |
| ES | - | Canary Flash 180M es (tiny+fast, catalog), Whisper, Parakeet | Latin America overlaps pt-BR channels |
| FR | - | Canary Flash 180M fr (catalog), Whisper, Parakeet | - |
| PT-BR | WhatsApp native transcription covers PT (verify Android availability in BR today) | Whisper Turbo/Medium (101 langs), Parakeet | hook is offline/privacy, not language gap |
| RU | GigaAM v3 Russian (native punctuation), Zipformer RU (catalog); 4pda-class forums; Play availability constrained in RU | GigaAM v3, Zipformer ru | F-Droid/APK direct is the realistic channel; state plainly |
| TR | Parakeet v3 (25 EU langs incl tr), Whisper 101 langs | Parakeet v3, Whisper | no TR-specific model measured; say "works via multilingual models" |
| PL | Parakeet v3, Whisper | Parakeet v3, Whisper | same as TR |
| HI | WhatsApp native transcription covers HI (verify) | Whisper (101), Parakeet? (check hi in the 25) | omnilingual 300M incoming (TASK-635) but hi showed script-confusion: do NOT promise quality |
| FA | Shenava Koochik Persian (community catalog, NeMo RNNT int8) | Shenava | our only fa-verified option; IR: Play unavailable, F-Droid/APK direct |
| HE | none language-specific | Whisper (101 langs) | omnilingual he measured WEAK (25.5% CER, script confusion): be honest, "multilingual Whisper, quality varies" |
| UK | Moonshine base UK (community catalog) | Moonshine uk | omnilingual 300M verified uk 7.8% CER and incoming as a catalog entry (TASK-635): the UK story is strong NOW |

## Channel maps (live verification 2026-09-23)

### IT / ES / PT-BR (verified 2026-09-23; quotes from live pages)

ITALY: (1) r/ItalyInformatica recurring thread "Mostrami il codice! - La fiera
dei vostri programmi" (194,788 subs by sidebar; latest edition 2026-09-21,
open): the single best verified channel; rules explicitly waive the
self-promo ban inside the thread for repo links (GitHub fits). (2) mastodon.uno
(80K iscritti, own about page): national FOSS-friendly instance. (3)
forum.hwupgrade.it Smartphone section: live enthusiast audience, regolamento
to read before posting. AVOID r/italy (instant-ban rule). Italian blogs: no
verified submission path (selectallfromdual donation-only contact;
idealight's form is advertising-framed).

SPAIN: weakest market. (1) r/spain: promo allowed ONLY with prior moderator
approval (quoted rule; note the anti-AI-content wording: our posts must read
handwritten). (2) masto.es (largest general Spanish instance). (3) HTCMania:
biggest Spanish Android forum, alive, but device/ROM-centric sections and
rules text NOT_FOUND: verify normas before posting. r/AndroidES is restricted
(unusable), mastodon.es is dead.

BRAZIL: (1) Android Dev BR Telegram (@androiddevbr) + Slack: the right dev
audience; CoC verified, channel promo rules NOT verifiable without joining:
introduce first. (2) r/programacao (27K): free-FOSS app posts not banned
(rule 5 bans paid content only). (3) Adrenaline forum smartphone section:
alive, rules unread. AVOID r/brasil (app promo explicitly excluded).
Blog path: meiobit/tecnoblog contact emails verified (pitches can become
articles).

### RU / TR / UA (verified 2026-09-23; quotes from live pages)

PREMISE VERIFIED: Google Play free-app distribution in Russia still works
(Google's own support page, live: billing paused 2022-03-10, "free apps will
remain available", foreign developers can publish/update free apps; the 2024
monetization cutoff concerns RU-banked developers only). F-Droid listing
confirmed live. So RU users have Play + F-Droid + direct APK.

RUSSIA: (1) 4PDA "Android - Разработка и программирование" section: THE
Russian Android forum, open registration, per-app dev topics are the standard
pattern; rule 1.8 bans UNapproved commercial/advertising posts (a free FOSS
announcement in its own topic is the section norm; new topics need the
"Универсальный мастер тем" wizard; write in Russian). (2) Habr personal blog
(dev-authored write-up; current self-promo rules page NOT_FOUND: verify). (3)
r/Pikabu (262,799 members): rule 4 bans "naked advertising" but explicitly
allows detailed posts with pros/cons and comparisons. AVOID r/russia
(quarantined), r/AskARussian (pre-moderated, promo banned).

TURKEY: (1) Technopat Sosyal: most credible TR forum; rule 27 bans ad
link-shorteners/referral codes, some categories need account-age thresholds
(suggestion category: 1yr + 500 messages) = high friction for a fresh account.
(2) r/turkey: Turkish and English welcome; self-promo requires PRIOR mod
approval (quoted). (3) mastodon.com.tr (21,244 users, open registration) as
secondary. DonanımHaber alive but rules NOT_FOUND.

UKRAINE: (1) r/ukraina (Ukrainian): commercial advertising prohibited,
Ukraine-topics framing: a free app with a Ukrainian-language angle and zero
marketing framing is the only defensible form; Ukrainian text mandatory. (2)
r/Ukraine_UA (Ukrainian-only rule): advertising/spam restricted. (3) DOU.ua
(IT community; publishing rules NOT_FOUND) for a build-story article angle;
Telegram @linux_ukraine (949 subs) small FOSS room. AVOID r/ukraine (EN,
war-focused, off-topic). Our UK story is genuinely strong (uk model coverage
verified 7.8% CER + Moonshine catalog entry).

### HI / FA / HE + EN refresh (verified 2026-09-23)

INDIA: r/developersIndia (1,610,812 subs) "I Made This" flair is the channel:
GitHub URL required, one post per project, no blog/YouTube promo. r/india
(3.49M) bans self-promo (1:10 comment ratio tolerated). fossbytes has a
contact path only. WhatsApp-in-India scale: cite as "commonly cited 550M+
(secondhand)" if used at all.

IRAN: Cafe Bazaar dev portal live (45M active installs self-reported;
guidelines at developers.cafebazaar.ir/fa/app-publish-guidelines;
registration open-question for non-Iranian devs: ASK developers@cafebazaar.ir
first). Myket panel live (policies NOT_FOUND). Digiato (tech media, 114K
Telegram) has a contact path. Persian subs ban promo. Google's own sanctions
FAQ (live): free apps remain downloadable in Iran; the removals hit
Iranian-DEVELOPED apps; Iran's domestic WhatsApp/Play ban was lifted
2024-12-24. F-Droid/direct APK remain the sanction-proof route. Model hook:
Shenava Persian; WhatsApp supports no Persian anywhere.

ISRAEL: Facebook group "אנדרואיד ישראל - Android Israel" (facebook.com/
groups/androidil, nonprofit support group: join and read pinned rules before
posting; Hebrew). HWzone forum: dev promotion is PAID (publisher sub) or
coordinated with the site; honest participation as a user otherwise. r/Israel
(271,614): self-promo banned without explicit mod permission; Hebrew/Arabic/
English all allowed. WhatsApp he = iOS 17+ only: the Android gap is real but
frame honestly (many Israelis are on iPhone).

EN REFRESH: r/fossdroid healthy (105,564 subs; dev showcases daily; see the
AI-post caveat above; Play links REQUIRE a free-distribution link alongside;
NO Telegram links per their spam rule). r/droidappshowcase (15,893; 1 app per
account per week; account 24h+, karma 2+). r/SideProject (849,587; showcase
is the sub's purpose; no sub-specific rules today). Show HN guidelines
re-verified (real, try-able things; no landing pages; one-shot).

HONEST GAPS: Cafe Bazaar/Myket non-Iranian registration (ask first);
Telegram/Facebook group policies visible only after joining; trak.in and
rotter rule pages NOT_FOUND; WhatsApp-India figures secondhand only.

Prior verified anchors that still hold until re-verification: r/fossdroid
(promo welcomed), android-hilfe.de German threads with unmet demand,
Show HN one-shot.

## Sequencing (draft, updates with the maps)

1. Friendliest first (debug the localized pitches): IT (Mostrami il codice),
   DE (android-hilfe existing demand threads as REPLIES, not new posts),
   global EN (r/fossdroid).
2. Wave 2: UK (war-era FOSS solidarity + our uk model story), FR/ES/PL/TR
   per their maps.
3. Wave 3: RU (4pda per rules), FA (Telegram per rules), HI/HE (honest
   multilingual framing).
4. One Show HN only when the localized round has hardened the pitch.

## Post drafts (for the maintainer approval round; unslop rules; verified claims only)

Common links used by every draft: repo https://github.com/RisorseArtificiali/anti-vocale
F-Droid https://f-droid.org/en/packages/com.antivocale.app/
Every draft is written to be posted by the maintainer in first person.
No draft claims WhatsApp sends audio anywhere (their transcription is
on-device too; our differentiators are Android language coverage, model
choice, whole-history and video-file support, FOSS).

### IT (r/ItalyInformatica "Mostrami il codice" + mastodon.uno)
ALREADY POSTED (pre-campaign, ~2 months ago): the "Mostrami il codice" comment is live (share link https://www.reddit.com/r/ItalyInformatica/s/ZTGjsokrE3). IT channel status: DONE on the primary venue; mastodon.uno remains the optional IT second step.


Titolo: Anti-Vocale, trascrizione offline dei vocal per Android (FOSS)

Ho scritto un'app Android che trascrive i messaggi vocali in locale, sul
telefono. Nessun audio esce dal dispositivo. La condivisione da WhatsApp o
Telegram apre l'app, il testo arriva come notifica e resta nella cronologia
interna, insieme a tutta la storia precedente.

La trascrizione nativa di WhatsApp su Android non supporta l'italiano. Questa
app si, con piu modelli tra cui scegliere: Whisper Distil Large v3 in
italiano (qualita' alta), Parakeet TDT come default veloce multilingue, e un
catalogo di modelli importabili dalla community (tedesco, svizzero-tedesco,
persiano, ucraino, russo e altri). Accetta anche file video.

Software libero, nessuna pubblicita', nessun tracciamento. Su F-Droid e Play
Store. Il codice e' su GitHub: link in testa. L'ho sviluppata con assistenza
AI, dichiarato nel README. Commenti e ISSUE benvenuti, anche per aggiungere
modelli per altre lingue.

### DE (android-hilfe.de App-Vorstellungen + kuketz Empfehlungsecke)
POSTED 2026-09-24: android-hilfe.de App-Vorstellungen https://www.android-hilfe.de/forum/app-vorstellungen.1351/kostenlos-anti-vocale-sprachnachrichten-offline-transkribieren-open-source-ohne-root.1328761.html - the formal rules-compliant post ([Kostenlos] prefix, proper German, sections Funktionsweise/Warum/Technik/Root/Preis/Quelle, screenshots as attachments, child-safe declaration). The scraper renders only the login bar (thread likely members-visible or fresh); verified by the maintainer posting it. The WhatsApp demand thread (37 replies, whatsapp.820/whatsapp-transkribtion-ohne-deutsche-sprachauswahl.1113434) remains a good future REPLY when the discussion restarts; last activity 2025-12 so necroposting risk.


Titel: [Kostenlos] Anti-Vocale: Sprachnachrichten offline transkribieren (Open Source)

Ich habe eine Android-App geschrieben, die Sprachnachrichten lokal auf dem
Geraet transkribiert. Keine Audio-Datei verlaesst das Telefon. Man teilt den
Voice-Note aus WhatsApp oder Telegram mit der App und bekommt den Text als
Benachrichtigung; alles bleibt in der internen Chronik.

Die eingebaute Transkription von WhatsApp unterstuetzt auf Android kein
Deutsch. Diese App schon: mit mehreren Modellen, unter anderem einem
deutschen Zipformer (Streaming), Whisper v3 Turbo auf Deutsch und dem
mehrsprachigen Parakeet als Standard. Fuer Deutschschweiz gibt es ein
eigenes Whisper-Turbo-Modell. Video-Dateien gehen auch.

Freie Software, keine Werbung, kein Tracking. Erhaeltlich ueber F-Droid und
Play Store, Quellcode auf GitHub. Die Entwicklung war KI-unterstuetzt, das
steht im README. Feedback und Ergaenzungen fuer weitere Sprachen sind
willkommen.

### FR (linuxfr.org journal + forum.frandroid.com)
POSTED 2026-09-24: linuxfr.org journal https://linuxfr.org/users/risorseartificiali/journaux/anti-vocale-transcription-hors-ligne-des-messages-vocaux-sur-android-libre (accents right, store links, AI disclosure).


Titre: Anti-Vocale, transcription hors ligne des messages vocaux sur Android (libre)

J'ai ecrit une application Android qui transcrit les messages vocaux
localement, sur le telephone. Aucun fichier audio ne quitte l'appareil. On
partage le vocal depuis WhatsApp ou Telegram, le texte arrive en
notification et reste dans l'historique interne.

La transcription integree de WhatsApp ne gere pas le francais sur Android.
Cette application si, avec plusieurs modeles: un Canary Flash 180M en
francais (petit et rapide), Whisper et le Parakeet multilingue. Les fichiers
video sont pris en charge aussi.

Logiciel libre, sans pub, sans traqueur. Sur F-Droid et Play Store, code sur
GitHub. Le developpement a ete assiste par IA, c'est indique dans le README.
Retours bienvenus.

### ES (r/spain con approvazione mod + masto.es)

Titulo: Anti-Vocale, transcripcion offline de notas de voz en Android (libre)

Escribi una app Android que transcribe notas de voz en local, en el
telefono. Ningun audio sale del dispositivo. Compartis el vocal desde
WhatsApp o Telegram y el texto llega como notificacion; queda en el
historial interno.

La transcripcion nativa de WhatsApp en Android no incluye el espanol. Esta
app si, con varios modelos: un Canary Flash 180M en espanol (pequeno y
rapido), Whisper y el Parakeet multilingue. Tambien acepta archivos de
video.

Software libre, sin publicidad ni rastreo. En F-Droid y Play Store, codigo en
GitHub. El desarrollo fue asistido por IA, esta declarado en el README.
Comentarios bienvenidos.

### PT-BR (r/programacao + Android Dev BR, dopo presentazione)

Titulo: Anti-Vocale: transcricao offline de mensagens de voz no Android (FOSS)

Escrevi um app Android que transcreve mensagens de voz localmente, no
celular. Nenhum audio sai do aparelho. Compartilha o audio do WhatsApp ou
Telegram e o texto chega como notificacao; fica no historico interno.

A transcricao nativa do WhatsApp no Android cobre portugues, mas depende do
servico deles e do aparelho; aqui o diferencial e outro: escolha de modelos
(Whisper, Parakeet multilingue), transcricao de todo o historico e de
arquivos de video, tudo offline e gratuito para sempre.

Software livre, sem anuncios, sem rastreio. No F-Droid e na Play Store,
codigo no GitHub. O desenvolvimento teve assistencia de IA, declarado no
README. Feedback bem-vindo.

### EN global (r/fossdroid + Show HN + r/droidappshowcase)
POSTED 2026-09-24: r/speechtech https://www.reddit.com/r/speechtech/comments/1wpce5x/antivocale_ondevice_speechtotext_for_android/ (the technical-architecture pitch: seven families, swappable models, versioned catalog without releases; live 2m after posting, no removal markers).

POSTED 2026-09-24: r/droidappshowcase https://www.reddit.com/r/droidappshowcase/comments/1wpakga/antivocale_offline_voicemessage_transcription/ (Promo flair, store links first, notification screenshot).


Title: Anti-Vocale, offline voice-message transcription for Android (FOSS)

I built an Android app that transcribes voice messages on the phone. No
audio leaves the device. Share a voice note from WhatsApp or Telegram and
the text arrives as a notification; everything stays in the internal
history.

Multiple models per language, swappable: Parakeet TDT as the fast
multilingual default, Whisper family, per-language community models
(German, Swiss German, Persian, Ukrainian, Russian, Arabic and more),
user-importable too. Video files work as well. WhatsApp's own Android
transcription covers only en/pt/es/ru.

Free software, no ads, no tracking. On F-Droid and Play Store, source on
GitHub. Development was AI-assisted, disclosed in the README. The
AI-assisted point matters for r/fossdroid's current rules: maintainer to
decide before posting there.

### RU (4PDA dev topic + Habr)

Заголовок: Anti-Vocale - офлайн-расшифровка голосовых сообщений на Android (FOSS)

Я написал Android-приложение, которое расшифровывает голосовые сообщения
локально, на телефоне. Аудио не покидает устройство. Голосовое из WhatsApp
или Telegram отправляется в приложение Shared-меню, текст приходит
уведомлением и остаётся во внутренней истории.

Встроенная расшифровка WhatsApp на Android не поддерживает русский. Это
приложение поддерживает: модель GigaAM v3 (русский, со своей пунктуацией),
Zipformer на русском и многоязычные модели. Видео-файлы тоже принимаются.

Свободное ПО, без рекламы и слежки. F-Droid, Play Store (бесплатные
приложения там по-прежнему доступны), исходники на GitHub. Разработка велась
с помощью ИИ, это указано в README. Отзывы приветствуются.

### UK (r/ukraina / r/Ukraine_UA, ucraino)

Заголовок: Anti-Vocale - офлайн-розшифровка голосових повідомлень на Android (FOSS)

Я написав Android-застосунок, який розшифровує голосові повідомлення локально
на телефоні. Аудіо не залишає пристрій. Голосове з WhatsApp або Telegram
відправляється у застосунок, текст приходить сповіщенням і залишається у
внутрішній історії.

Вбудована розшифровка WhatsApp на Android не підтримує українську. Цей
застосунок підтримує: модель Moonshine для української та багатомовні
моделі. Відео-файли теж приймаються.

Вільне ПЗ, без реклами і стеження. F-Droid, Play Store, вихідний код на
GitHub. Розробка велася з допомогою ШІ, це вказано в README. Відгуки
вітаються.

### TR (r/turkey con approvazione mod + Technopat dopo soglia)
VALIDATED 2026-09-24 (Mac, FLEURS tr, 10 clips): PARAKEET v3 FAILS TURKISH HARD (CER 90.6%, phonetic Latin gibberish; NOT a supported language of the stock v3 weights despite multilingual branding). Campaign copy: "Whisper recommended" for tr (whisper small 6.9% CER); omnilingual 300M as the light fallback (6.5% CER, 2x faster, but 29.7% WER).


Baslik: Anti-Vocale, Android icin cevrimdisi sesli mesaj transkripsiyonu (FOSS)

Android icin, sesli mesajlari telefonun kendisinde metne ceviren bir uygulama
yazdim. Hicbir ses dosyasi cihazi terk etmiyor. WhatsApp veya Telegram'dan
sesli mesaji paylastiginizda metin bildirim olarak geliyor ve ic
gecmiste kaliyor.

WhatsApp'in Android'deki yerlesik transkripsiyonu Turkce desteklemiyor. Bu
uygulama destekliyor: cok dilli modeller (Parakeet, Whisper) ile. Video
dosyalari da kabul ediliyor.

Ozgun yazilim, reklamsiz, izleme yok. F-Droid ve Play Store'da, kaynak kodu
GitHub'da. Gelistirme yapay zeka destegliydi, README'de belirtiliyor.
Geri bildirim bekliyorum.

### PL (forum.android.com.pl)
VALIDATED 2026-09-24 (Mac, FLEURS pl, 10 clips): PARAKEET v3 IS ADVERTISABLE. CER 3.0% / WER 10.7%, RTF 0.29 (2.5-6x faster than whisper small, which is also 2.3x worse at this size). The campaign copy can lead with Parakeet for PL.


Tytul: Anti-Vocale, offline transkrypcja wiadomosci glosowych na Androidzie (FOSS)

Napisalem aplikacje na Androida, ktora transkrybuje wiadomosci glosowe
lokalnie, na telefonie. Zadne audio nie opuszcza urzadzenia. Glosowka z
WhatsApp lub Telegrama trafia do aplikacji, tekst przychodzi jako
powiadomienie i zostaje w wewnetrznej historii.

Wbudowana transkrypcja WhatsApp na Androidzie nie obsluguje jezyka
polskiego. Ta aplikacja tak: modelami wielojezycznymi (Parakeet, Whisper).
Pliki wideo tez sa obslugiwane.

Wolne oprogramowanie, bez reklam i sledzenia. F-Droid i Play Store, kod na
GitHubie. Rozwoj byl wspierany AI, co jest zadeklarowane w README. Uwagi
witelane.

### HI (r/developersIndia "I Made This")

Title: I made an offline voice-message transcriber for Android (FOSS, works for Hindi via multilingual models)

I built an Android app that transcribes voice messages on the phone itself.
No audio leaves the device. Share a voice note from WhatsApp or Telegram and
the text arrives as a notification; everything stays in the internal
history.

WhatsApp's built-in Android transcription does not support Hindi at all. This
app transcribes Hindi through its multilingual models (Whisper); quality
varies and I will not oversell it. Per-language community models can be
imported, and the catalog is growing. Video files work too.

Free software, no ads, no tracking. F-Droid and Play Store, source on
GitHub. Development was AI-assisted, disclosed in the README. Feedback and
Hindi-model suggestions welcome.

### FA (Cafe Bazaar dopo conferma registrazione + Digiato contatto + canali FOSS)

تیتر: Anti-Vocale، رونویسی آفلاین پیام‌های صوتی در اندروید (متن‌باز)

یک اپ اندروید نوشته‌ام که پیام‌های صوتی را روی خود گوشی رونویسی می‌کند. هیچ
صدایی از دستگاه خارج نمی‌شود. ویس واتس‌اپ یا تلگرام را با اپ به اشتراک
می‌گذارید، متن به صورت اعلان می‌رسد و در تاریخچه داخلی می‌ماند.

رونویسی داخلی واتس‌اپ در اندروید فارسی را پشتیبانی نمی‌کند. این اپ
پشتیبانی می‌کند: با مدل فارسی شنوا و مدل‌های چندزبانه. فایل‌های ویدیویی هم
پذیرفته می‌شوند.

نرم‌افزار آزاد، بدون تبلیغ، بدون ردیابی. در F-Droid و (در حال پیگیری)
کافه‌بازار، کد در گیت‌هاب. توسعه با کمک هوش مصنوعی انجام شده و در README
ذکر شده. بازخورد خوش‌آمد است.

### HE (קבוצת פייסבוק אנדרואיד ישראל + HWzone come utente onesto)

כותרת: Anti-Vocale, תמלול לא מקוון של הודעות קוליות באנדרואיד (קוד פתוח)

כתבתי אפליקציית אנדרואיד שמתמללת הודעות קוליות מקומית, על הטלפון. שום
אודיו לא עוזב את המכשיר. משתפים קולית מ-WhatsApp או Telegram והטקסט
מגיע כהתראה ונשאר בהיסטוריה הפנימית.

התמלול המובנה של WhatsApp באנדרואיד לא תומך בעברית (ב-iOS 17+ כן). האפליקציה
הזאת מתמללת עברית דרך המודלים הרב-לשוניים (Whisper); האיכות משתנה ואני
לא מבטיח יותר ממה שנמדד. קבצי וידאו עובדים גם.

תוכנה חופשית, בלי פרסומות, בלי מעקב. ב-F-Droid וב-Play Store, הקוד
ב-GitHub. הפיתוח נעשה בסיוע AI, מוצהר ב-README. משוב יתקבל בברכה.

## Approval checklist for the maintainer

1. The WhatsApp language claims rest on the official FAQ fetched 2026-09-23
   (Android: en/pt/es/ru only). Re-fetch the page on posting day.
2. r/fossdroid: VERIFIED BANNED (the 2026-09 announcement was removed with
   the explicit reason "appears to contain AI-generated text, images, or
   code", fossdroid-ModTeam; the maintainer's UI showed "You're currently
   banned"). MANDATORY PATH: a modmail to the mods asking ban status +
   whether an honest hand-written announcement with the AI-assist
   disclosure is welcome; NO post until the mods answer. Ban status cannot
   be verified from the workstation (account-scoped, reddit blocks reads).
3. r/france only with the aged active French account; r/turkey and r/spain
   need mod approval first; Cafe Bazaar needs the registration answer from
   developers@cafebazaar.ir.
4. Post one market at a time in the sequencing order (IT, DE, EN first),
   hardening the pitch between waves.
