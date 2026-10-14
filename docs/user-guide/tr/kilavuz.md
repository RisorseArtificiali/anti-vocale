# Anti-Vocale Kullanım Kılavuzu

Anti-Vocale, sesli mesajları Android cihazınızda tamamen çevrimdışı olarak metne çevirir. Ses hiçbir zaman telefonunuzdan çıkmaz: transkripsiyon yerelde, açık yapay zeka modelleriyle çalışır; hesap yok, bulut hizmeti yok, telemetri yok.

Kılavuz 1.15 sürümü için güncellendi.

## İçindekiler

1. [Başlangıç](#başlangıç)
2. [Model seçimi](#model-seçimi)
3. [Transkripsiyon: günlük akış](#transkripsiyon-günlük-akış)
4. [WhatsApp sesli mesajları, otomatik](#whatsapp-sesli-mesajları-otomatik)
5. [Sonuçlar: etiketler, biçimler ve yüzen pencere](#sonuçlar-etiketler-biçimler-ve-yüzen-pencere)
6. [Uzun sesler, kuyruk ve yeniden denemeler](#uzun-sesler-kuyruk-ve-yeniden-denemeler)
7. [Topluluk modelleri ve içe aktarma](#topluluk-modelleri-ve-içe-aktarma)
8. [Tasker ve otomasyon](#tasker-ve-otomasyon)
9. [Uygulama bazlı ayarlar](#uygulama-bazlı-ayarlar)
10. [Gizlilik](#gizlilik)
11. [Sorun giderme](#sorun-giderme)
12. [SSS](#sss)

Bölüm 8a, 8b ve 8c otomasyon yüzeylerini kapsar: zamanlanmış klasör izleme, uzak aktarma ve Open Transcribe sağlayıcısı.

## Başlangıç

1. Anti-Vocale'i mağazanızdan (Google Play veya F-Droid) ya da GitHub'daki sürüm APK'larından birinden yükleyin.
2. Uygulamayı bir kez açın. **Model** sekmesinde indirmeye hazır yerleşik modelleri görürsünüz.
3. Bir model indirin. Çoğu kişi için önerilen ilk tercih **Parakeet TDT (stock int8, 640 MB)**: hızlı, küçük ve 25 Avrupa dilini kapsıyor.
4. Transkripsiyon için herhangi bir mesajlaşma uygulamasından (WhatsApp, Telegram, Signal ve diğerleri) bir sesli mesajı Anti-Vocale ile paylaşın. İşlem sürerken bir bildirim görünür, ardından metni taşıyan ikinci bir bildirim gelir.
5. Metni kopyalamak, paylaşmak ya da geldiği sohbete geri göndermek için sonuç bildirimine dokunun.

Başka bir yapılandırma gerekmez. Aşağıdaki her şey isteğe bağlıdır.

## Model seçimi

Modeller boyut, hız, dil kapsamı ve doğruluk bakımından farklıdır. Model sekmesi, indirmeden önce her kartta temel bilgileri gösterir. Hızlı bir yol gösterme:

| Model | Boyut | Diller | Notlar |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 MB | 25 Avrupa | Hızlı ve hafif; varsayılan öneri |
| Parakeet TDT SmoothQuant | 862 MB | 25 Avrupa | Daha doğru ama daha ağır; daha çok RAM ister |
| Whisper Turbo | 988 MB | 101 | Whisper ailesindeki en iyi denge |
| Whisper Medium | 903 MB | 101 | Turbo'dan yavaş, çoğu ses için daha iyi değil |
| Whisper Small | 358 MB | 101 | En hafif Whisper; makul kalite |
| Whisper Distil Italian | 938 MB | Yalnızca İtalyanca | Yerleşik kümedeki en iyi İtalyanca doğruluğu |
| Qwen3-ASR | 938 MB | Çok dilli | Alternatif mimari |
| Nemotron streaming | 640 MB | Çok dilli | Siz konuşurken metni gösterir (streaming) |
| GigaAM v3 | 326 MB | Rusça | Rusça uzmanı |

Pratik kurallar:
- Çoğunlukla tek bir dilde transkripsiyon yapıyorsanız, aynı boyuttaki genel amaçlı bir model yerine uzman bir model (Distil Italian, GigaAM) daha iyi sonuç verir.
- Telefonunuzda 4 GB veya daha az RAM varsa, 500 MB altındaki modelleri tercih edin.
- Gemma modelleri (Model sekmesinde ayrı listelenir) transkripsiyon da yapabilen daha büyük dil modelleridir. Denemeler için ilginçtirler ama özel ASR modellerinden daha ağır ve daha yavaştır.

## Transkripsiyon: günlük akış

- Bir sesli mesajı Anti-Vocale ile paylaşın. İşleme, ekran kapalıyken bile hemen başlar.
- Sonuç bildirimi şunları sunar: **Copy**, **Share** ve kaynak uygulama desteklendiğinde, metni sesli mesajın geldiği sohbete doğrudan yapıştıran **Send to [App]**.
- Auto-Copy açıkken (Settings) metin, bildirim geldiğinde zaten panonuzdadır; bildirim bunu belirtir.
- Her transkripsiyon, kullanılan model, süre ve işlem süresiyle birlikte **Logs** sekmesinde tutulur. Bir kayda uzun basarak yeniden deneyebilir, kopyalayabilir, silebilir veya kötü bir sonucu e-postayla bildirebilirsiniz; **Yeniden metne çıkar** aynı sesi seçtiğiniz başka bir modelle yeniden işler.
- **Whisper veya SenseVoice** modeliyle, açılan her transkript bir dil rozeti taşır: modelin algıladığı dilin bayrağı; tahmin yanlışsa tek dokunuşla kontrol edebilir veya sabitleyebilirsiniz (küçük modeller bazen yanılgıya düşer). Diğer model aileleri algılanan dili bildirmez, bu yüzden rozet onlarda çıkmaz; Settings'teki anahtar bunu belirtir ve devre dışı kalır.
- Auto-Save açıkken (Settings) her transkript ayrıca seçtiğiniz bir klasöre .txt dosyası olarak yazılır.

## WhatsApp sesli mesajları, otomatik

Anti-Vocale, yeni WhatsApp sesli mesajlarını geldikleri anda, hiç dokunmadan transkript edebilir. Metin Kayıtlar sekmesine düşer ve bir sonuç bildirimi gelir; Android arka planda başlatmayı engellerse (örneğin ekran kapalıyken), tek dokunuşluk bir bildirim transkripsiyonu onun yerine başlatır.

Kurulum bir dakika sürer, **Ayarlar > Sesli mesajlar** altında:

1. **Voice note transcription** seçeneğini açın ve Android sorduğunda ses erişimini verin. İki kez reddettiyseniz Android artık iletişim kutusu göstermez; onun yerine görünen kırmızı ipucu, iznin hâlâ verilebileceği sistem sayfasını açar.
2. Yönlendirilen seçici aracılığıyla WhatsApp Voice Notes klasörünü bir kez seçin. Yanlış klasör anında reddedilir; klasörü aynı karttan istediğiniz zaman değiştirebilir veya kaldırabilirsiniz.
3. İsteğe bağlı: **sender recognition** seçeneğini de açın ve bildirim erişimi verin. Logs satırı böylece gönderenin adıyla etiketlenir.

Etiketleri iki dürüst kural yönetir. Bir ad yalnızca mesajın kendi bildirimi onu doğruladığında görünür; yani bildirim özellik açıkken gelmiş olmalı ve bildirdiği uzunluk sese uymalıdır: adlar için asla tahmin yürütülmez. Ayrıca elle indirdiğiniz mesajlar (auto-download kapalıyken) etiketsiz kalır, çünkü bildirimleri artık güvenilemeyecek kadar eskimiştir. Son bildirimlerin bekleme listesi yalnızca bellekte tutulur ve özellik kapalıyken kaybolur.

Gizlilik, her yerdeki sözleşmenin aynısıdır: algılama açıkken yalnızca WhatsApp sesli mesaj klasörünün listesi okunur; bir mesajın sesi yalnızca onu transkript etmek için okunur ve tamamen bu cihazda kalır; özellik kapalıyken hiçbir şey okunmaz.

Bir uyarıyı açıkça belirtelim: agresif pil yöneticileri olan telefonlarda, sistem uygulamayı askıya almışken gelen mesajlar uygulama uyanana kadar kaçabilir. Otomatik moda güveniyorsanız, Anti-Vocale'i pil optimizasyonunun dışında tutmak bu modu güvenilir kılar. Şimdilik yalnızca WhatsApp; Telegram yol haritasında.

## Sonuçlar: etiketler, biçimler ve yüzen pencere

- **Speaker labels**: Settings'ten açın; sohbet transkriptleri cümle bazında konuşmacı ataması alır ve sonuçta ya da her dışa aktarmada SPEAKER sıraları olarak gösterilir.
- **Metinden fazlasını paylaşma**: bir sonuç satırından düz metin, zamanlanmış metin, SRT veya VTT olarak paylaşın. Zamanlanmış biçimler ipucu (cue) taşıyan bir transkripsiyon gerektirir. "Şu olarak paylaş…" seçeneği Settings'ten açılır.
- **Picture-in-Picture**: bir transkripsiyon sürerken yüzen bir pencere, metnin canlı olarak oluşunu gösterebilir; böylece başka uygulamaları kullanmaya devam edebilirsiniz.
- **Yeniden metne çıkar**: herhangi bir kaydı, satırından doğrudan başka bir modelle yeniden işleyin ve karşılaştırın.

## Uzun sesler, kuyruk ve yeniden denemeler

- Her ses uzunluğu her modelle çalışır: uzun kayıtlar otomatik olarak bölünür ve birleştirilir. (Eski sürümlerde Parakeet için 6:40 sınırı vardı; o sınır kalktı.)
- Arka arkaya birkaç mesaj paylaşın: kuyruğa girerler. Başka bir transkripsiyon sürerken, kuyruktaki her öğe kendi bildiriminden tek tek iptal edilebilir.
- Başarısız olan bir transkripsiyon, Kayıtlar sekmesinden tek dokunuşla yeniden denenebilir.

## Topluluk modelleri ve içe aktarma

Yerleşik katalog her dili kapsamaz. Anti-Vocale, iki dokunuşla içe aktarabileceğiniz ek modellerden oluşan bir topluluk kataloğuyla gelir: Model sekmesi, Advanced, ONNX Sherpa, Import from catalog, dilinize göre süzün, modele dokunun, onaylayın. Katalog sürekli büyür ve bugün 30'dan fazla doğrulanmış kayıt barındırır: Arapça (lehçe ağırlıklı, tam boy ve hafif), Almanca (streaming ve bir Whisper ince ayarı), İspanyolca (streaming ve hafif Moonshine), İsviçre Almancası, İbranice, Farsça, Rusça, Vietnamca, Korece, Ukraynaca, altı Hint dili (Bengalce, Guceratça, Hintçe, Marathi, Tamil, Telugu), İngilizce ve Fransızca için Canary Flash, SenseVoice, Dolphin, Orukeet (İtalyanca dahil 24 dili kapsayan bir Parakeet ince ayarı) ve düşük RAM'li çok dilli bir Whisper Tiny. Diliniz katalogda yoksa bir issue açın: kayıtlar böyle eklenir.

İleri düzey kullanıcılar ayrıca şunları yapabilir:
- bir modeli bir Hugging Face deposu URL'sinden veya katalog kaydı bağlantısından içe aktarmak (aynı iletişim kutusundaki ileri düzey dal);
- telefondaki bir klasörden bir dizi model dosyasını içe aktarmak;
- indirilmiş bir modeli **offline bundle** olarak dışa aktarıp başka bir cihazda içe aktarmak; böylece bir kez indirilen model, yeniden indirilmeden taşınır;
- uygulamayı, başka birinin (örneğin topluluğunuzun) sürdürdüğü farklı bir katalog dizinine yönlendirmek (katalog kaynağının yanındaki "change" eylemi).

İçe aktarma biçimi ve dosya gereksinimleri [external models](../../external-models.md) belgesinde anlatılmıştır.

## Tasker ve otomasyon

Anti-Vocale, Tasker'in (veya herhangi bir otomasyon uygulamasının) arayüze dokunmadan bir dosyayı transkript etmek için gönderebileceği bir yayın (broadcast) kabul eder:

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/path/to/audio, task_id=your-id
Optional: backend_id=<model id> to pick the model for that request
```

Sonuç, bir yanıt yayını olarak geri döner. Örneklerle birlikte tam anlatım [Tasker guide](../../TASKER_GUIDE.md) dosyasındadır.

## Zamanlanmış klasör izleme

Anti-Vocale, seçtiğiniz klasörleri izleyebilir ve yeni ses dosyalarını otomatik olarak transkript edebilir: Ayarlar, Zamanlanmış klasör izleme, Bir klasörü izle. Taramalar, sistemin izin verdiği zamanlamayla çalışır; agresif pil tasarrufu modları bunları geciktirebilir ve manuel Şimdi tara her zaman çalışır. Her yeni ses dosyası, o an seçili olan modelle transkript edilir ve diğer tüm işlemler gibi Kayıtlar sekmesine düşer. Android 11 ve üzerinde klasör seçici, bir mesajlaşma uygulamasının medya klasörü gibi paylaşılan depolama konumlarını açabilir; böylece bir sohbetin sesli mesajlarını, her birini ayrıca paylaşmadan otomatik olarak transkript etmek mümkün olur.

## Uzak aktarma (deneysel)

Kendi transkripsiyon sunucunuzu çalıştırıyorsanız, Anti-Vocale uzun veya ağır transkripsiyonları ona devredebilir: Settings, Remote offload (experimental). /v1/audio/transcriptions API'sini konuşan her OpenAI uyumlu sunucu çalışır (OmniVoice, whisper.cpp sunucusu ve diğerleri). Uygulama içindeki açıklama bunu açıkça söyler: özellik etkinleştirildiğinde, devrettiğiniz transkripsiyonların sesi yalnızca girdiğiniz sunucu adresine gönderilir, o sunucu nerede olursa olsun (ev ağınız ya da internetteki herhangi bir yer); geri kalan her şey cihaz üzerinde transkript edilir ve özellik varsayılan olarak kapalıdır.

## Open Transcribe (deneysel)

Açık Open Transcribe sözleşmesini konuşan uygulamalar, örneğin Forkgram (bir Telegram istemcisi), Anti-Vocale'i kendi sesli mesaj transkript aracı olarak kullanabilir: **Settings > Automation > Open Transcribe API** seçeneğini etkinleştirin (varsayılan olarak kapalıdır), ardından istemcinin çevrimdışı transkripsiyon ayarlarından Anti-Vocale'i seçin. Sesli mesajlar, uygulamanın kendi modelleriyle transkript edilir ve ses cihazdan hiç çıkmaz. Anahtarı kapatmak, uygulamayı istemcilerin seçicilerinden yeniden kaldırır.

## Uygulama bazlı ayarlar

Paylaştığınız her uygulama için (WhatsApp, Telegram, ...) ayrı ayrı yapılandırabilirsiniz: geri gönderme eyleminin gösterilip gösterilmeyeceğini, otomatik kopyalamanın yapılıp yapılmayacağını ve bildirim sesini. Settings sekmesi, Per-app settings.

## Gizlilik

- Transkripsiyon %100 cihaz üzerindedir. Hiçbir ses, metin veya üst veri telefonunuzdan asla çıkmaz.
- Uygulamanın transkripsiyon için internet izni yoktur; ağa yalnızca açıkça bir model indirdiğinizde çıkılır.
- Kayıtlar cihazınızda kalır ve size aittir: Kayıtlar sekmesinden istediğiniz zaman temizleyin.
- Play derlemesi Crashlytics çökme bildirimini içerir (Android ayarlarından görebilir ve devre dışı bırakabilirsiniz); F-Droid derlemesinde yoktur.

## Sorun giderme

**Transkripsiyon hiç bitmiyor / bildirim kayboluyor.**
Bazı telefon markaları (Vivo, OPPO, bazı Xiaomi ve Samsung modelleri) arka plan uygulamalarını agresif biçimde askıya alır. Anti-Vocale'i bir kez açın ve sunulursa pil muafiyetini verin; ya da uygulamayı pil ayarlarında bulup Unrestricted olarak ayarlayın. Uygulama bu durumu algılar ve gerçekleştiğinde bir bildirimle açıklar.

**"Yeterli bellek yok" veya büyük modellerle çökmeler.**
Modeller boyutlarını kartlarında listeler. 4 GB veya daha az RAM'e sahip telefonlarda 500 MB altındaki modelleri kullanın. Bir transkripsiyon bellek yetersizliği iletisiyle başarısız olursa, daha kısa bir dosyayı, daha küçük bir modeli deneyin veya diğer uygulamaları kapatın.

**Transkripsiyon kalitesi düşük.**
Diliniz için uzman bir model deneyin (yukarıdaki tabloya bakın). Logs'taki kötü kayda uzun basın ve ayrıntıları bize göndermek için Report özelliğini kullanın (model, süre, işlem zamanı; transkript alıntısı yalnızca eklemeyi seçerseniz).

**NNAPI çökmelere yol açıyor.**
Settings'te NNAPI sağlayıcısını etkinleştirdiyseniz ve uygulama artık çöküyorsa, bir sonraki açılışta otomatik olarak CPU'ya geri döner. NNAPI büyük ölçüde telefonun çip setine bağlıdır; tekrar tekrar çöküyorsa CPU'da bırakın.

**Otomatik sesli mesajlar gelmeyi bıraktı.**
İki olağan neden. WhatsApp klasörü silinip yeniden oluşturulduysa (yeniden kurulum, temizlik aracı), saklanan klasör izni geçersiz olabilir: Ayarlar > Sesli mesajlar'u açın ve klasörü karttan yeniden seçin. Telefonunuz arka plan uygulamalarını agresif biçimde askıya alıyorsa, uygulama askıdayken gelen mesajlar uygulama uyanana kadar kaçar: yukarıdaki pil muafiyeti çözümdür ve bu özellik için, manuel paylaşımdan daha önemlidir.

## SSS

**İnternet olmadan çalışır mı?**
Evet. Bir model indirdikten sonra transkripsiyon tamamen çevrimdışı çalışır.

**Hangi mesajlaşma uygulamaları destekleniyor?**
Ses dosyası paylaşabilen her uygulama. Geri gönderme eylemi şu anda belirli bir uygulama alt kümesini hedefliyor (WhatsApp, Telegram ve otomatik algılanan diğerleri).

**Transkriptlerim nerede?**
Kayıtlar sekmesinde ve isteğe bağlı olarak seçtiğiniz bir klasördeki .txt dosyalarında. Başka hiçbir yerde bir şey saklanmaz.

**Sesli mesajlar, geldikleri anda otomatik olarak transkript edilebilir mi?**
Evet, WhatsApp için: Ayarlar > Sesli mesajlar'u açın, ses erişimini verin ve WhatsApp Voice Notes klasörünü bir kez seçin. Yeni mesajlar yerine ulaştıkça transkript edilir ve satır, gönderenin adını taşıyabilir. Zamanlanmış klasör izleme, herhangi bir uygulamanın medya klasörü için genel yol olmaya devam eder (Android 11+) ve Telegram yol haritasındadır. Agresif pil yöneticileri olan telefonlarda, uygulama askıdayken gelen mesajlar kaçabilir: pil muafiyeti otomatik modu güvenilir kılar.

**Bir sesli mesajda neden gönderen adı yok?**
Ad yalnızca, gönderen tanıma açıkken mesajın bildirimi geldiyse ve bildirilen uzunluk sese uyuyorsa görünür. Elle indirilen mesajlar (auto-download kapalı) ve bildirimi hiç gelmemiş mesajlar bilerek adsız kalır: yanlış bir ad, hiç ad olmamasından daha kötüdür.

**Neden iki mağaza var (Play ve F-Droid)?**
Aynı uygulama, aynı özellikler. F-Droid, uygulamayı sahipli bileşen olmadan kaynaktan derler; Play, otomatik çökme bildirimi ekler.

**Gerçekten gizli mi?**
Evet. Kaynak kodu açıktır; hiçbir verinin cihazdan çıkmadığını doğrulayabilirsiniz. Depodaki gizlilik ilkesine bakın.

---

Bir hata mı buldunuz ya da bir şey mi eksik? [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues) üzerinde bir issue açın.
