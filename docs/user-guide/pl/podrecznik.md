# Podręcznik użytkownika Anti-Vocale

Anti-Vocale transkrybuje wiadomości głosowe na Twoim urządzeniu z Androidem, całkowicie offline. Dźwięk nigdy nie opuszcza telefonu: transkrypcja działa lokalnie na otwartych modelach AI, bez konta, bez usług w chmurze, bez telemetrii.

Podręcznik zaktualizowany do wersji 1.15.

## Spis treści

1. [Pierwsze kroki](#pierwsze-kroki)
2. [Wybór modelu](#wybór-modelu)
3. [Transkrypcja: codzienny przepływ](#transkrypcja-codzienny-przepływ)
4. [Wiadomości głosowe WhatsApp, automatycznie](#wiadomości-głosowe-whatsapp-automatycznie)
5. [Wyniki: etykiety, formaty i pływające okno](#wyniki-etykiety-formaty-i-pływające-okno)
6. [Długie audio, kolejka i ponawianie](#długie-audio-kolejka-i-ponawianie)
7. [Modele społeczności i importy](#modele-społeczności-i-importy)
8. [Tasker i automatyzacja](#tasker-i-automatyzacja)
9. [Ustawienia per aplikacja](#ustawienia-per-aplikacja)
10. [Kopia zapasowa i przenoszenie](#kopia-zapasowa-i-przenoszenie)
11. [Prywatność](#prywatność)
12. [Rozwiązywanie problemów](#rozwiązywanie-problemów)
13. [FAQ](#faq)

Sekcje 8a, 8b i 8c opisują płaszczyzny automatyzacji: zaplanowane obserwowanie folderów, przekazywanie zdalne oraz dostawcę Open Transcribe.

## Pierwsze kroki

1. Zainstaluj Anti-Vocale ze swojego sklepu (Google Play lub F-Droid) albo z opublikowanego pliku APK na GitHubie.
2. Otwórz aplikację raz. Na karcie **Modele** zobaczysz wbudowane modele dostępne do pobrania.
3. Pobierz jeden model. Dla większości osób zalecanym pierwszym wyborem jest **Parakeet TDT (stock int8, 640 MB)**: szybki, mały i obsługuje 25 języków europejskich.
4. Aby transkrybować, udostępnij wiadomość głosową z dowolnej aplikacji do wiadomości (WhatsApp, Telegram, Signal i inne) do Anti-Vocale. Podczas przetwarzania pojawia się powiadomienie, a następnie drugie powiadomienie z tekstem.
5. Dotknij powiadomienia z wynikiem, aby skopiować, udostępnić lub odesłać tekst do czatu, z którego pochodzi.

Dalsza konfiguracja nie jest potrzebna. Wszystko poniżej jest opcjonalne.

## Wybór modelu

Modele różnią się rozmiarem, szybkością, pokryciem językowym i dokładnością. Karta Modele pokazuje przed pobraniem najważniejsze dane o każdym modelu. Szybkie rozeznanie:

| Model | Rozmiar | Języki | Uwagi |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 MB | 25 europejskich | Szybki i lekki; domyślna rekomendacja |
| Parakeet TDT SmoothQuant | 862 MB | 25 europejskich | Dokładniejszy, cięższy; wymaga więcej RAM |
| Whisper Turbo | 988 MB | 101 | Najlepszy balans w rodzinie Whisper |
| Whisper Medium | 903 MB | 101 | Wolniejszy od Turbo, nie lepszy dla większości nagrań |
| Whisper Small | 358 MB | 101 | Najlżejszy Whisper; przyzwoita jakość |
| Whisper Distil Italian | 938 MB | tylko włoski | Najlepsza dokładność włoskiego w zestawie wbudowanym |
| Qwen3-ASR | 938 MB | wielojęzyczny | Alternatywna architektura |
| Nemotron streaming | 640 MB | wielojęzyczny | Pokazuje tekst w trakcie mówienia (streaming) |
| GigaAM v3 | 326 MB | rosyjski | Specjalista od rosyjskiego |

Kilka praktycznych wskazówek:
- Jeśli transkrybujesz głównie jeden język, model specjalistyczny (Distil Italian, GigaAM) jest lepszy od modelu ogólnego o tym samym rozmiarze.
- Jeśli Twój telefon ma 4 GB RAM lub mniej, wybieraj modele poniżej 500 MB.
- Modele Gemma (wypisane osobno na karcie Modele) to większe modele językowe, które też potrafią transkrybować. Są ciekawe do eksperymentów, ale cięższe i wolniejsze od dedykowanych modeli ASR.

## Transkrypcja: codzienny przepływ

- Udostępnij wiadomość głosową do Anti-Vocale. Przetwarzanie zaczyna się natychmiast, nawet przy wyłączonym ekranie.
- Powiadomienie z wynikiem oferuje przyciski **Kopiuj**, **Udostępnij** oraz, gdy aplikacja źródłowa jest obsługiwana, akcję **Wyślij do [Aplikacja]**, która wkleja tekst bezpośrednio do czatu, z którego pochodziła wiadomość głosowa.
- Przy włączonym automatycznym kopiowaniu (Ustawienia) tekst jest już w schowku, gdy przychodzi powiadomienie; powiadomienie o tym informuje.
- Każda transkrypcja trafia do karty **Historia** z nazwą użytego modelu, długością nagrania i czasem przetwarzania. Przytrzymaj wpis, aby ponowić, skopiować, usunąć albo zgłosić zły wynik e-mailem; **Przepisz ponownie** przepuszcza to samo audio przez inny model wybrany przez Ciebie.
- Z modelem **Whisper lub SenseVoice** każda rozwinięta transkrypcja ma znacznik języka: flagę języka wykrytego przez model; dotknij jej, aby sprawdzić lub przypiąć język, gdy rozpoznanie jest błędne (małe modele czasem się mylą). Inne rodziny modeli nie raportują wykrytego języka, więc znacznik pozostaje dla nich wyłączony; przełącznik w Ustawieniach tak właśnie mówi i jest nieaktywny.
- Modele, które same wykrywają język, mogą się pomylić. Gdy język transkrypcji wyraźnie różni się od języka telefonu, jednozdaniowa wskazówka pod tekstem odsyła do ustawienia języka dla modeli, które je respektują. Wskazówka sama z siebie nigdy niczego nie zmienia.
- Udostępnij to samo audio dwa razy i nic nie zostanie uruchomione ponownie bez pytania: jeśli ten plik był już transkrybowany tym samym modelem, mały dialog proponuje otwarcie istniejącego wyniku albo ponowną transkrypcję. Jeśli coś się zmieniło (inny model, wcześniejsze nieudane uruchomienie), transkrypcja po prostu startuje.
- Z automatycznym zapisem (Ustawienia) każda transkrypcja zapisuje się dodatkowo jako plik .txt w wybranym przez Ciebie folderze.

## Wiadomości głosowe WhatsApp, automatycznie

Anti-Vocale może transkrybować nowe notatki głosowe WhatsApp w chwili ich nadejścia, bez żadnego dotykania. Tekst trafia do karty Historia i do powiadomienia z wynikiem; jeśli Android zablokuje uruchomienie w tle (na przykład przy wyłączonym ekranie), transkrypcję uruchamia powiadomienie jednym dotknięciem.

Konfiguracja zajmuje minutę, w **Ustawienia > Notatki głosowe**:

1. Włącz **Transkrypcję notatek głosowych** i przyznaj dostęp do audio, gdy Android zapyta. Jeśli odmówisz dwa razy, Android przestanie pokazywać okno dialogowe; czerwona wskazówka, która pojawia się w zamian, otwiera stronę systemową, na której uprawnienie nadal można przyznać.
2. Wybierz raz folder Voice Notes WhatsApp w kreatorze wyboru. Nieprawidłowy folder jest od razu odrzucany; możesz go zmienić lub usunąć w dowolnej chwili z tej samej karty.
3. Opcjonalnie: włącz też **rozpoznawanie nadawcy** i przyznaj dostęp do powiadomień. Wiersz w Historii dostaje wtedy etykietę z imieniem nadawcy.

Dwie uczciwe zasady rządzą etykietami. Imię pojawia się tylko wtedy, gdy potwierdza je powiadomienie samej notatki, czyli gdy powiadomienie przyszło przy włączonej funkcji, a jego zgłoszona długość zgadza się z audio: imion nigdy się nie zgaduje. Notatki pobrane ręcznie (przy wyłączonym auto-pobieraniu) pozostają bez etykiety, bo ich powiadomienie jest za stare, aby mu ufać. Lista oczekujących ostatnich powiadomień istnieje tylko w pamięci i znika po wyłączeniu funkcji.

Prywatność to ten sam układ co wszędzie indziej: przy włączonym wykrywaniu czytana jest wyłącznie lista plików folderu notatek głosowych WhatsApp; audio notatki jest czytane tylko po to, aby ją transkrybować, w całości na tym urządzeniu; przy wyłączonej funkcji nie jest czytane nic.

Jedno zastrzeżenie, powiedziane wprost: na telefonach z agresywnymi menedżerami baterii notatki przychodzące w czasie uśpienia aplikacji mogą zostać pominięte do jej przebudzenia. Jeśli polegasz na trybie automatycznym, wyłączenie Anti-Vocale z optymalizacji baterii czyni go niezawodnym. Na razie tylko WhatsApp; Telegram jest w planach.

## Wyniki: etykiety, formaty i pływające okno

- **Identyfikuj mówców**: włącz w Ustawieniach, a transkrypcje rozmów dostaną przypisanie mówcy do każdego zdania, renderowane jako tury SPEAKER w wyniku i w każdym eksporcie.
- **Odtwarzanie**: oryginalne audio odtwarza się prosto z rozwiniętego wiersza. Odtwarzanie i pauza, pasek pozycji do przewijania, prędkość odtwarzania oraz tryb pomijania ciszy dla nagrań z długimi pauzami, który przeskakuje przerwy zamiast ich odtwarzać.
- **Udostępniaj więcej niż tekst**: z wiersza wyniku udostępnij jako zwykły tekst, tekst ze znacznikami czasu, SRT lub VTT. Formaty ze znacznikami czasu wymagają transkrypcji, która te znaczniki zawiera. „Udostępnij jako…” włącza się w Ustawieniach.
- **Picture-in-Picture**: podczas transkrypcji pływające okno może pokazywać tekst pojawiający się na żywo, dzięki czemu możesz dalej używać innych aplikacji.
- **Przepisz ponownie**: ponów dowolny wpis innym modelem prosto z jego wiersza i porównaj.

## Długie audio, kolejka i ponawianie

- Dowolna długość audio działa z każdym modelem: dłuższe nagrania są automatycznie dzielone i sklejane. (Starsze wersje miały limit 6:40 przy Parakeet; ten limit zniknął.)
- Udostępnij kilka wiadomości jedna po drugiej: ułożą się w kolejkę. Każdy element kolejki można osobno anulować z jego powiadomienia, gdy działa inna transkrypcja.
- Nieudaną transkrypcję można ponowić jednym dotknięciem z karty Historia.

## Modele społeczności i importy

Wbudowany katalog nie obejmuje każdego języka. Anti-Vocale zawiera katalog społecznościowy z dodatkowymi modelami, które importujesz dwoma dotknięciami: karta Modele, Zaawansowane, ONNX Sherpa, Importuj z katalogu, filtruj po swoim języku, dotknij modelu, potwierdź. Katalog stale rośnie i liczy dziś ponad 30 zweryfikowanych pozycji: arabski (dialektalny, pełnowymiarowy i lekki), niemiecki (streaming i fine-tune Whispera), hiszpański (streaming i lekki Moonshine), szwajcarski niemiecki, hebrajski, perski, rosyjski, wietnamski, koreański, ukraiński, sześć języków Indii (bengalski, gudżarati, hindi, marathi, tamilski, telugu), Canary Flash dla angielskiego i francuskiego, SenseVoice, Dolphin, Orukeet (fine-tune Parakeeta obejmujący 24 języki, w tym włoski) oraz wielojęzyczny Whisper Tiny o niskim zużyciu RAM. Jeśli brakuje Twojego języka, otwórz issue: w ten sposób dodaje się kolejne pozycje.

Zaawansowani użytkownicy mogą też:
- zaimportować model z adresu URL repozytorium Hugging Face lub z linku do pozycji katalogu (gałąź zaawansowana w tym samym oknie);
- zaimportować zestaw plików modelu z folderu na telefonie;
- wyeksportować pobrany model jako **pakiet offline** i zaimportować go na innym urządzeniu, dzięki czemu model pobrany raz przenosi się bez ponownego pobierania;
- wskazać aplikacji inny indeks katalogu (akcja „zmień” obok źródła katalogu) prowadzony przez kogokolwiek, na przykład Twoją społeczność.

Format importu i wymagania dotyczące plików są opisane w [modelach zewnętrznych](../../external-models.md).

## Tasker i automatyzacja

Anti-Vocale przyjmuje transmisję (broadcast), którą Tasker (lub dowolna aplikacja automatyzująca) może wysłać, aby transkrybować plik bez otwierania interfejsu:

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/path/to/audio, task_id=your-id
Optional: backend_id=<model id> to pick the model for that request
```

Wynik wraca jako broadcast odpowiedzi. Pełny przewodnik z przykładami znajduje się w [przewodniku Tasker](../../TASKER_GUIDE.md).

## Zaplanowane obserwowanie folderów

Anti-Vocale może obserwować wybrane przez Ciebie foldery i automatycznie transkrybować nowe pliki audio: Ustawienia, Zaplanowane obserwowanie folderów, Obserwuj folder. Skanowania odbywają się w rytmie, na jaki pozwala system; agresywne oszczędzanie baterii może je opóźniać, a ręczne Skanuj teraz działa zawsze. Każdy nowy plik audio jest transkrybowany aktualnie wybranym modelem i trafia do karty Historia jak każde inne uruchomienie. Na Androidzie 11 lub nowszym kreator wyboru folderów może otwierać lokalizacje pamięci współdzielonej, takie jak folder multimediów aplikacji do wiadomości, co pozwala automatycznie transkrybować notatki głosowe z czatu bez udostępniania każdej z nich osobno.

## Przekazywanie zdalne (eksperymentalne)

Jeśli prowadzisz własny serwer transkrypcji, Anti-Vocale może zlecać mu długie lub wymagające transkrypcje: Ustawienia, Przekazywanie zdalne (eksperymentalne). Działa każdy serwer zgodny z OpenAI, obsługujący API /v1/audio/transcriptions (OmniVoice, serwer whisper.cpp i inne). Komunikat w aplikacji mówi to wprost: gdy funkcja jest włączona, audio delegowanych transkrypcji trafia wyłącznie na adres serwera, który wpisujesz, gdziekolwiek ten serwer jest (w Twojej sieci domowej lub w dowolnym miejscu internetu); wszystko inne jest transkrybowane na urządzeniu, a funkcja jest domyślnie wyłączona.

## Open Transcribe (eksperymentalne)

Aplikacje obsługujące otwarty kontrakt Open Transcribe, jak Forkgram (klient Telegrama), mogą używać Anti-Vocale jako swojego transkrybatora wiadomości głosowych: włącz **Ustawienia > Automatyzacja > API Open Transcribe** (domyślnie wyłączone), a potem wybierz Anti-Vocale w ustawieniach transkrypcji offline w kliencie. Wiadomości głosowe są transkrybowane modelami samej aplikacji i audio nigdy nie opuszcza urządzenia. Wyłączenie przełącznika ponownie usuwa aplikację z list wyboru w klientach.

## Ustawienia per aplikacja

Dla każdej aplikacji, z której udostępniasz (WhatsApp, Telegram, ...), możesz osobno skonfigurować: czy pokazywać akcję odesłania, czy kopiować automatycznie oraz dźwięk powiadomienia. Karta Ustawienia, Ustawienia per aplikacja.

## Kopia zapasowa i przenoszenie

Ustawienia i cała historia transkrypcji mogą przenieść się na inny telefon. **Ustawienia > Kopia zapasowa i przenoszenie** eksportuje archiwum zip do wybranego przez ciebie folderu; import na dowolnym urządzeniu scala wiersze z lokalną historią, a dwukrotny import tego samego archiwum niczego nie zmienia. Traktuj plik archiwum jak dane, które zawiera: transkrypcje, nazwy nadawców i etykiety mówców są w nim jawnym tekstem, odczyta je każdy, kto ma plik, o czym sam eksport informuje przed zapisem.

## Prywatność

- Transkrypcja odbywa się w 100% na urządzeniu. Żadne audio, żaden tekst i żadne metadane nigdy nie opuszczają Twojego telefonu.
- Aplikacja nie ma uprawnienia do internetu dla transkrypcji; sieć jest używana tylko wtedy, gdy sam pobierasz model.
- Historia pozostaje na Twoim urządzeniu i jest Twoja: wyczyść ją w dowolnej chwili z karty Historia.
- Ręczne archiwum kopii zapasowej (Ustawienia > Kopia zapasowa i przenoszenie) zawiera transkrypcje, nazwy nadawców i etykiety mówców jawnym tekstem: odczyta je każdy, kto ma plik. Aplikacja nigdzie nigdy nie wysyła archiwum; trafia ono tam, gdzie je zapiszesz.
- Wersja z Play zawiera raportowanie awarii Crashlytics (możesz je zobaczyć i wyłączyć w ustawieniach Androida); wersja z F-Droid nie zawiera żadnego.

## Rozwiązywanie problemów

**Transkrypcja nigdy się nie kończy / powiadomienie znika.**
Niektórzy producenci telefonów (Vivo, OPPO, część Xiaomi i Samsungów) agresywnie usypiają aplikacje w tle. Otwórz Anti-Vocale raz i, jeśli aplikacja zaproponuje, przyznaj wyłączenie z optymalizacji baterii; albo znajdź aplikację w ustawieniach baterii i ustaw ją na Bez ograniczeń. Aplikacja wykrywa tę sytuację i wyjaśnia ją w powiadomieniu, gdy się pojawi.

**„Za mało pamięci” lub awarie przy dużych modelach.**
Rozmiar modelu jest podany na jego karcie. Na telefonach z 4 GB RAM lub mniej używaj modeli poniżej 500 MB. Jeśli transkrypcja kończy się komunikatem o braku pamięci, spróbuj krótszego pliku, mniejszego modelu albo zamknij inne aplikacje.

**Jakość transkrypcji jest słaba.**
Spróbuj modelu specjalistycznego dla swojego języka (patrz tabela wyżej). Przytrzymaj słaby wpis w Historii i użyj Zgłoś transkrypcję, aby przesłać nam szczegóły (model, długość, czasy przetwarzania; fragment transkrypcji tylko jeśli zdecydujesz się go dołączyć).

**NNAPI powoduje awarie.**
Jeśli włączyłeś dostawcę NNAPI w Ustawieniach, a aplikacja teraz się wysypuje, przy następnym starcie automatycznie wraca do CPU. NNAPI mocno zależy od chipsetu telefonu; jeśli awarie się powtarzają, zostań przy CPU.

**Automatyczne notatki głosowe przestały się pojawiać.**
Dwie typowe przyczyny. Jeśli folder WhatsApp został usunięty i utworzony ponownie (ponowna instalacja, narzędzie do czyszczenia), zapisane uprawnienie do folderu może przestać działać: otwórz Ustawienia > Notatki głosowe i wybierz folder ponownie z karty. Jeśli telefon agresywnie usypia aplikacje w tle, notatki przychodzące, gdy aplikacja jest uśpiona, są gubione do jej przebudzenia: rozwiązaniem jest opisane wyżej wyłączenie z optymalizacji baterii, które dla tej funkcji ma większe znaczenie niż przy ręcznym udostępnianiu.

## FAQ

**Czy działa bez internetu?**
Tak. Po pobraniu modelu transkrypcja działa całkowicie offline.

**Które aplikacje do wiadomości są obsługiwane?**
Każda, która potrafi udostępnić plik audio. Akcja odesłania jest obecnie dostępna dla części aplikacji (WhatsApp, Telegram i inne wykrywane automatycznie).

**Gdzie są moje transkrypcje?**
W karcie Historia, a opcjonalnie jako pliki .txt w wybranym folderze. Nic nie jest zapisywane nigdzie indziej.

**Czy potrafi transkrybować notatki głosowe automatycznie, gdy przychodzą?**
Tak, dla WhatsApp: włącz Ustawienia > Notatki głosowe, przyznaj dostęp do audio i wybierz raz folder Voice Notes WhatsApp. Nowe notatki są transkrybowane na bieżąco, a wiersz może nosić imię nadawcy. Zaplanowane obserwowanie folderów pozostaje ogólną drogą dla folderu multimediów dowolnej aplikacji (Android 11+), a Telegram jest w planach. Na telefonach z agresywnymi menedżerami baterii notatki przychodzące, gdy aplikacja jest uśpiona, mogą zostać pominięte: wyłączenie z optymalizacji baterii czyni tryb automatyczny niezawodnym.

**Dlaczego notatka głosowa nie ma imienia nadawcy?**
Imię pojawia się tylko wtedy, gdy powiadomienie notatki przyszło przy włączonym rozpoznawaniu nadawcy, a jego zgłoszona długość zgadza się z audio. Notatki pobrane ręcznie (auto-pobieranie wyłączone) i notatki, których powiadomienie nigdy nie przyszło, celowo nie dostają imienia: złe imię jest gorsze niż brak imienia.

**Dlaczego są dwa sklepy (Play i F-Droid)?**
Ta sama aplikacja, te same funkcje. F-Droid buduje ją ze źródeł bez własnościowych komponentów; Play dodaje automatyczne raportowanie awarii.

**Czy na pewno jest prywatna?**
Tak. Kod źródłowy jest otwarty; możesz sprawdzić, że żadne dane nie opuszczają urządzenia. Zobacz politykę prywatności w repozytorium.

---

Znalazłeś błąd albo czegoś brakuje? Otwórz issue na [GitHubie](https://github.com/RisorseArtificiali/anti-vocale/issues).
