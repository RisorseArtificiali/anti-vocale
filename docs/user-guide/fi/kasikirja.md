# Anti-Vocale-käyttöohje

Anti-Vocale transkriboi ääniviestit Android-laitteellasi kokonaan ilman verkkoyhteyttä. Ääni ei koskaan lähde puhelimestasi: transkriptio tapahtuu paikallisesti avoimilla tekoälymalleilla, ilman tiliä, ilman pilvipalvelua, ilman telemetriaa.

Käsikirja päivitetty versiolle 1.15.

## Sisällys

1. [Käytön aloittaminen](#käytön-aloittaminen)
2. [Mallin valitseminen](#mallin-valitseminen)
3. [Transkribointi: päivittäinen työnkulku](#transkribointi-päivittäinen-työnkulku)
4. [WhatsApp-ääniviestit automaattisesti](#whatsapp-ääniviestit-automaattisesti)
5. [Tulokset: merkinnät, muodot ja kelluva ikkuna](#tulokset-merkinnät-muodot-ja-kelluva-ikkuna)
6. [Pitkä ääni, jono ja uudelleenyritykset](#pitkä-ääni-jono-ja-uudelleenyritykset)
7. [Yhteisömallit ja tuonnit](#yhteisömallit-ja-tuonnit)
8. [Tasker ja automaatio](#tasker-ja-automaatio)
9. [Sovelluskohtaiset asetukset](#sovelluskohtaiset-asetukset)
10. [Varmuuskopiointi ja siirto](#varmuuskopiointi-ja-siirto)
11. [Tietosuoja](#tietosuoja)
12. [Vianetsintä](#vianetsintä)
13. [Usein kysytyt kysymykset](#usein-kysytyt-kysymykset)

Osat 8a, 8b ja 8c käsittelevät automaatiota: ajastetun kansioiden valvonnan, etäsuorituksen ja Open Transcribe -tarjoajan.

## Käytön aloittaminen

1. Asenna Anti-Vocale kaupastasi (Google Play tai F-Droid) tai GitHubissa julkaistusta APK-tiedostosta.
2. Avaa sovellus kerran. **Mallit**-välilehdellä näet sisäänrakennetut mallit, jotka ovat ladattavissa.
3. Lataa yksi malli. Suositeltu ensivalinta useimmille on **Parakeet TDT (stock int8, 640 Mt)**: nopea, pieni ja kattaa 25 eurooppalaista kieltä.
4. Transkriboitaksesi jaa ääniviesti mistä tahansa viestisovelluksesta (WhatsApp, Telegram, Signal ja muut) Anti-Vocaleen. Käsittelyn aikana näkyy ilmoitus ja sitten toinen ilmoitus tekstin kanssa.
5. Napauta tulosilmoitusta kopioidaksesi, jakaaksesi tai lähettääksesi tekstin takaisin keskusteluun, josta se tuli.

Muuta määritystä ei tarvita. Kaikki tämän jälkeen on valinnaista.

## Mallin valitseminen

Mallit eroavat toisistaan koon, nopeuden, kielikattavuuden ja tarkkuuden mukaan. Mallit-välilehti näyttää olennaiset faktat kustakin kortista ennen latausta. Pikakatsaus:

| Malli | Koko | Kielet | Huomiot |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 Mt | 25 eurooppalaista | Nopea ja kevyt; oletussuositus |
| Parakeet TDT SmoothQuant | 862 Mt | 25 eurooppalaista | Tarkempi, raskaampi; tarvitsee enemmän RAM-muistia |
| Whisper Turbo | 988 Mt | 101 | Paras tasapaino Whisper-malliperheessä |
| Whisper Medium | 903 Mt | 101 | Hitaampi kuin Turbo, ei parempi useimmille äänille |
| Whisper Small | 358 Mt | 101 | Kevyin Whisper-malli; kohtuullinen laatu |
| Whisper Distil Italian | 938 Mt | Vain italia | Sisäänrakennetun joukon paras italian tarkkuus |
| Qwen3-ASR | 938 Mt | Monikielinen | Vaihtoehtoinen arkkitehtuuri |
| Nemotron streaming | 640 Mt | Monikielinen | Näyttää tekstin puhuessasi (streaming) |
| GigaAM v3 | 326 Mt | Venäjä | Venäjän kielen erikoismalli |

Nyrkkisäännöt:
- Jos transkriboit enimmäkseen yhtä kieltä, erikoistunut malli (Distil Italian, GigaAM) päihittää samankokoisen yleismallin.
- Jos puhelimessasi on 4 Gt RAM-muistia tai vähemmän, suosi alle 500 Mt:n malleja.
- Gemma-mallit (listattu erikseen Mallit-välilehdellä) ovat suurempia kielimalleja, jotka osaavat myös transkriboida. Kokeiluun kiinnostavia, mutta raskaampia ja hitaampia kuin puheentunnistukseen tehdyt ASR-mallit.

## Transkribointi: päivittäinen työnkulku

- Jaa ääniviesti Anti-Vocaleen. Käsittely alkaa heti, myös näyttö sammutettuna.
- Tulosilmoitus tarjoaa: **Kopioi**, **Jaa** ja silloin kun lähdesovellus on tuettu, **Lähetä sovellukseen [Sovellus]**, joka liittää tekstin suoraan siihen keskusteluun, josta ääniviesti tuli.
- Kun Automaattinen kopiointi on käytössä (Asetukset), teksti on jo leikepöydällä, kun ilmoitus saapuu; ilmoitus kertoo sen.
- Jokainen transkriptio säilyy **Historia**-välilehdellä käytetyn mallin, keston ja käsittelyajan kera. Pitkällä painalluksella merkinnän voi yrittää uudelleen, kopioida, poistaa tai raportoida huonona tuloksena sähköpostitse; **Transkriboi uudelleen** ajaa saman äänen itse valitsemallasi toisella mallilla.
- Kun käytössä on **Whisper- tai SenseVoice**-malli, jokainen laajennettu transkriptio kantaa kielimerkkiä: mallin tunnistaman kielen lippu, jota napauttamalla kielen voi tarkistaa tai kiinnittää, jos arvaus menee pieleen (pienet mallit erehtyvät joskus). Muut malliperheet eivät ilmoita tunnistettua kieltä, joten kielimerkki pysyy niissä poissa käytöstä; Asetusten kytkin kertoo tämän ja pysyy poissa käytöstä.
- Kielen itse tunnistavat mallit voivat arvioida sen väärin. Kun transkription kieli eroaa selvästi puhelimen kielestä, tekstin alla oleva yksirivinen vihje osoittaa kieliasetuksen niille malleille, jotka sitä noudattavat. Vihje ei muuta itse koskaan mitään.
- Jaa sama ääni kahdesti, eikä mitään suoriteta kahdesti kysymättä: jos kyseinen tiedosto on jo transkriboitu samalla mallilla, pieni valintaikkuna tarjoaa olemassa olevan tuloksen avaamista tai transkriboimista uudelleen. Jos jokin on muuttunut (eri malli, aiemmin epäonnistunut ajo), transkriptio käynnistyy suoraan.
- Kun Automaattinen tallennus kansioon on käytössä (Asetukset), jokainen transkriptio kirjoitetaan myös .txt-tiedostona valitsemaasi kansioon.

## WhatsApp-ääniviestit automaattisesti

Anti-Vocale voi transkriboida uudet WhatsApp-ääniviestit heti niiden saapuessa ilman ainuttakaan napautusta. Teksti päätyy Historia-välilehdelle ja tulosilmoitukseen; jos Android estää käynnistämisen taustalla (esimerkiksi näytön ollessa sammutettuna), yhden napautuksen ilmoitus käynnistää transkription sen sijaan.

Määritys vie minuutin, kohdassa **Asetukset > Ääniviestit**:

1. Ota käyttöön **Ääniviestien transkriptio** ja myönnä äänen käyttöoikeus, kun Android pyytää. Jos kielsit sen kahdesti, Android ei näytä valintaikkunaa enää; tilalle ilmestyvä punainen vihje avaa järjestelmäsivun, jolla käyttöoikeuden voi yhä myöntää.
2. Valitse ohjatun valitsimen avulla kerran kansio WhatsApp Voice Notes. Väärä kansio hylätään paikallaan; voit vaihtaa tai poistaa sen milloin tahansa samasta kortista.
3. Valinnainen: ota käyttöön myös **lähettäjän tunnistus** ja myönnä ilmoitusten käyttöoikeus. Historian rivi varustetaan silloin lähettäjän nimellä.

Nimeämistä ohjaa kaksi rehellistä sääntöä. Nimi näkyy vain, kun viestin oma ilmoitus vahvistaa sen, eli ilmoitus saapui ominaisuuden ollessa käytössä ja sen ilmoittama pituus vastaa ääntä: nimiä ei koskaan arvailla. Ja viestit, jotka lataat käsin (automaattinen lataus pois käytöstä), jäävät ilman nimeä, koska niiden ilmoitus on liian vanha luotettavaksi. Viimeaikaisten ilmoitusten jono elää vain muistissa ja katoaa, kun ominaisuus on pois käytöstä.

Tietosuoja on sama sopimus kuin kaikkialla muuallakin: kun seuranta on käytössä, sovellus lukee vain WhatsApp-ääniviestikansion sisältöluettelon; viestin ääni luetaan vain transkriboitavaksi, kokonaan tällä laitteella; kun ominaisuus on pois käytöstä, mitään ei lueta lainkaan.

Yksi varaus, suoraan sanottuna: puhelimilla, joiden akunhallinta on aggressiivista, viestit, jotka saapuvat sillä hetkellä, kun järjestelmä on keskeyttänyt sovelluksen, voivat jäädä väliin, kunnes sovellus herää. Jos nojaat automaattiseen tilaan, Anti-Vocalen sulkeminen akun optimoinnin ulkopuolelle tekee siitä luotettavan. Toistaiseksi vain WhatsApp; Telegram on suunnitelmissa.

## Tulokset: merkinnät, muodot ja kelluva ikkuna

- **Puhujamerkinnät**: ota ne käyttöön Asetuksista, ja keskustelujen transkriptiot saavat lausekohtaisen puhujatunnistuksen, esitettynä SPEAKER-vuoroina tuloksessa ja jokaisessa viennissä.
- **Toisto**: alkuperäinen ääni toistuu suoraan laajennetusta rivistä. Toisto ja tauko, sijaintipalkki kelaamiseen, toistonopeus ja hiljaisuudet ohittava tila pitkiä taukoja sisältäville tallenteille; se hyppää hiljaisuuksien yli toistamatta niitä.
- **Jakaminen muunakin kuin tekstinä**: tulosriviltä voit jakaa pelkkänä tekstinä, aikaleimattuna tekstinä, SRT:nä tai VTT:nä. Aikaleimatut muodot edellyttävät transkriptiota, jossa on aikakoodimerkinnät. ”Jaa muodossa…” otetaan käyttöön Asetuksista.
- **Picture-in-Picture**: transkription aikana kelluva ikkuna voi näyttää elävästi ilmestyvän tekstin, joten voit jatkaa muiden sovellusten käyttöä.
- **Transkriboi uudelleen**: yritä mitä tahansa merkintää toisella mallilla suoraan sen riviltä ja vertaa.

## Pitkä ääni, jono ja uudelleenyritykset

- Mikä tahansa äänen pituus toimii millä tahansa mallilla: pidemmät tallenteet pilkotaan ja liitetään yhteen automaattisesti. (Vanhemmissa versioissa oli 6:40:n raja Parakeetille; se on poistettu.)
- Jaa useita viestejä putkeen: ne asettuvat jonoon. Jokainen jonotettu kohde voidaan peruuttaa yksitellen sen omasta ilmoituksesta, kun toinen transkriptio on käynnissä.
- Epäonnistuneen transkription voi yrittää uudelleen yhdellä napautuksella Historia-välilehdeltä.

## Yhteisömallit ja tuonnit

Sisäänrakennettu valikoima ei kata kaikkia kieliä. Anti-Vocaleen sisältyy yhteisöluettelo lisämalleja, jotka tuot kahdella napautuksella: Mallit-välilehti, Lisäasetukset, ONNX Sherpa, Tuo luettelosta, suodata oman kielesi mukaan, napauta mallia, vahvista. Luettelo kasvaa jatkuvasti ja sisältää tänään yli 30 tarkistettua merkintää: arabia (murteellinen, täysikokoinen ja kevyt), saksa (streaming ja Whisper-fine-tune), espanja (streaming ja kevyt Moonshine), sveitsinsaksa, heprea, persia, venäjä, vietnam, korea, ukraina, kuusi intialaista kieltä (bengali, gudžarati, hindi, marathi, tamili, telugu), englannin ja ranskan Canary Flash, SenseVoice, Dolphin, Orukeet (Parakeet-fine-tune, joka kattaa 24 kieltä italian mukaan lukien) ja vähän RAM-muistia vaativa monikielinen Whisper Tiny. Jos kielesi puuttuu, avaa issue: niin merkinnät lisätään.

Edistyneet käyttäjät voivat myös:
- tuoda mallin Hugging Face -repositorion URL-osoitteesta tai luettelomerkinnän linkistä (edistynyt vaihtoehto samassa valintaikkunassa);
- tuoda joukon mallitiedostoja puhelimen kansiosta;
- viedä ladatun mallin **offline-pakettina** ja tuoda sen toiselle laitteelle, joten kerran ladattu malli siirtyy ilman uudelleenlatausta;
- ohjata sovelluksen toiseen luettelo-indeksiin (luettelolähteen vieressä oleva ”vaihda”-toiminto), jota ylläpitää kuka tahansa, esimerkiksi yhteisösi.

Tuontimuoto ja tiedostovaatimukset on dokumentoitu sivulla [ulkoiset mallit](../../external-models.md).

## Tasker ja automaatio

Anti-Vocale hyväksyy broadcast-viestin, jonka Tasker (tai mikä tahansa automaatiosovellus) voi lähettää transkriboidakseen tiedoston koskematta käyttöliittymään:

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/path/to/audio, task_id=your-id
Valinnainen: backend_id=<mallin tunnus> valitaksesi kyseiseen pyyntöön käytettävän mallin
```

Tulos palaa vastausbroadcastina. Koko läpikäynti esimerkkeineen on [Tasker-oppaassa](../../TASKER_GUIDE.md).

## Ajastettu kansioiden valvonta

Anti-Vocale voi valvoa valitsemiasi kansioita ja transkriboida uudet äänitiedostot automaattisesti: Asetukset, Ajastettu kansioiden valvonta, Valvo kansiota. Tarkistukset ajetaan järjestelmän sallimassa tahdissa; aggressiiviset virransäästäjät voivat viivästyttää niitä, ja manuaalinen Tarkista nyt toimii aina. Jokainen uusi äänitiedosto transkriboidaan parhaillaan valitulla mallilla ja päätyy Historia-välilehdelle kuin mikä tahansa muukin ajo. Android 11:ssä ja uudemmissa kansionvalitsin voi avata jaetun tallennustilan sijainteja, kuten viestisovelluksen mediakansion, mikä tekee keskustelun ääniviestien automaattisen transkription mahdolliseksi jakamatta jokaista viestiä erikseen.

## Etäsuoritus (kokeellinen)

Jos pyörität omaa transkriptiopalvelinta, Anti-Vocale voi siirtää pitkät tai raskaat transkriptiot sille: Asetukset, Etäsuoritus (kokeellinen). Toimii mikä tahansa OpenAI-yhteensopiva palvelin, joka käyttää /v1/audio/transcriptions-rajapintaa (OmniVoice, whisper.cpp-palvelin ja muut). Sovelluksen sisällä oleva tietosuojailmoitus sanoo sen suoraan: kun ominaisuus on käytössä, siirtämiesi transkriptioiden ääni lähetetään vain antamaasi palvelinosoitteeseen, missä ikinä tuo palvelin onkaan (kotiverkkosi tai missä tahansa internetissä); kaikki muu transkriboituu laitteella, ja ominaisuus on oletuksena pois käytöstä.

## Open Transcribe (kokeellinen)

Sovellukset, jotka puhuvat avointa Open Transcribe -sopimusta, kuten Forkgram (Telegram-asiakas), voivat käyttää Anti-Vocalea paikallisena ääniviestiensä transkriptorina: ota käyttöön **Asetukset > Automaatio > Open Transcribe -rajapinta** (pois käytöstä oletuksena) ja valitse sitten Anti-Vocale asiakkaan offline-transkriptioasetuksista. Ääniviestit transkriboidaan sovelluksen omilla malleilla, eikä ääni koskaan lähde laitteelta. Kytkimen sammuttamalla sovellus poistuu taas asiakkaiden valitsimista.

## Sovelluskohtaiset asetukset

Jokaiselle sovellukselle, josta jaat (WhatsApp, Telegram, …), voit määrittää erikseen: näytetäänkö takaisinlähetystoiminto, kopioidaanko automaattisesti ja mikä ilmoitusääni käytetään. Asetukset-välilehti, Sovelluskohtaiset asetukset.

## Varmuuskopiointi ja siirto

Asetuksesi ja koko transkriptiohistoriasi voivat siirtyä toiseen puhelimeen. **Asetukset > Varmuuskopiointi ja siirto** vie zip-arkiston valitsemaasi kansioon; sen tuonti mille tahansa laitteelle yhdistää rivit paikalliseen historiaan, ja saman arkiston tuonti kahdesti ei muuta mitään. Käsittele arkistotiedostoa kuin dataa, jota se sisältää: transkriptiot, lähettäjien nimet ja puhujamerkinnät ovat siinä selkokielisenä, ja jokainen tiedoston haltija voi lukea ne; vienti itse vahvistaa asian ennen kirjoittamista.

## Tietosuoja

- Transkriptio tapahtuu 100-prosenttisesti laitteella. Mikään ääni, teksti tai metatieto ei koskaan lähde puhelimestasi.
- Sovelluksella ei ole internetoikeutta transkriptiota varten; verkkoa käytetään vain, kun lataat mallin itse.
- Historia pysyy laitteellasi ja on sinun: tyhjennä se milloin tahansa Historia-välilehdeltä.
- Manuaalinen varmuuskopioarkisto (Asetukset > Varmuuskopiointi ja siirto) sisältää transkriptiot, lähettäjien nimet ja puhujamerkinnät selkokielisenä: jokainen, jolla on tiedosto, voi lukea ne. Sovellus ei lähetä arkistoa koskaan minnekään; se päätyy sinne, mihin sinä sen laitat.
- Play-versio sisältää Crashlytics-kaatumisraportoinnin (sen näkee ja poistaa käytöstä Androidin asetuksista); F-Droid-versiosta se puuttuu.

## Vianetsintä

**Transkriptio ei valmistu koskaan / ilmoitus katoaa.**
Jotkin puhelinmerkit (Vivo, OPPO, jotkut Xiaomi- ja Samsung-laitteet) keskeyttävät taustasovellukset aggressiivisesti. Avaa Anti-Vocale kerran ja myönnä akkupoikkeus, jos sovellus sitä tarjoaa; tai etsi sovellus akkuasetuksista ja aseta sen tilaksi Rajoittamaton. Sovellus tunnistaa tilanteen ja selittää sen ilmoituksessa silloin, kun se tapahtuu.

**"Muisti ei riitä" tai kaatumisia suurilla malleilla.**
Mallit ilmoittavat kokonsa kortissaan. Puhelimissa, joissa on 4 Gt RAM-muistia tai vähemmän, käytä alle 500 Mt:n malleja. Jos transkriptio epäonnistuu muistin loppumisviestillä, kokeile lyhyempää tiedostoa, pienempää mallia tai muiden sovellusten sulkemista.

**Transkription laatu on huono.**
Kokeile kielesi erikoismallia (katso yllä olevaa taulukkoa). Paina huonoa merkintää pitkään Historiassa ja käytä Raportoi-toimintoa lähettääksesi meille tiedot (malli, kesto, ajoitukset; transkriptiotekstin katkelma vain, jos valitset sen sisällyttämisen).

**NNAPI kaataa sovelluksen.**
Jos olet ottanut NNAPI-suorittimen käyttöön Asetuksissa ja sovellus alkaa kaatua, se palaa automaattisesti CPU:hen seuraavalla käynnistyksellä. NNAPI riippuu voimakkaasti puhelimen piiristöstä; jos kaatumiset toistuvat, jätä se CPU:hen.

**Automaattiset ääniviestit lakkasivat tulemasta.**
Kaksi tavallista syytä. Jos WhatsAppin kansio on poistettu ja luotu uudelleen (uudelleenasennus, siivoustyökalu), tallennettu kansion käyttöoikeus voi raueta: avaa Asetukset > Ääniviestit ja valitse kansio uudelleen kortista. Jos puhelimesi keskeyttää taustasovelluksia aggressiivisesti, sovelluksen keskeytyksen aikana saapuvat viestit jäävät väliin, kunnes se herää: yllä mainittu akkupoikkeus on ratkaisu, ja tälle ominaisuudelle se merkitsee enemmän kuin manuaaliselle jakamiselle.

## Usein kysytyt kysymykset

**Toimiiko se ilman verkkoyhteyttä?**
Kyllä. Mallin lataamisen jälkeen transkriptio toimii kokonaan ilman verkkoyhteyttä.

**Mitkä viestisovellukset ovat tuettuja?**
Mikä tahansa sovellus, joka voi jakaa äänitiedoston. Takaisinlähetystoiminto kohdistuu tällä hetkellä osajoukkoon sovelluksia (WhatsApp, Telegram ja muita, tunnistettu automaattisesti).

**Missä transkriptioni ovat?**
Historia-välilehdellä ja halutessasi .txt-tiedostoina valitsemassasi kansiossa. Mitään ei tallenneta muualle.

**Voiko se transkriboida ääniviestit automaattisesti niiden saapuessa?**
Kyllä, WhatsAppille: ota käyttöön Asetukset > Ääniviestit, myönnä äänen käyttöoikeus ja valitse kerran kansio WhatsApp Voice Notes. Uudet viestit transkriboidaan niiden saapuessa, ja rivi voi kantaa lähettäjän nimeä. Ajastettu kansioiden valvonta pysyy yleisenä reittinä minkä tahansa sovelluksen mediakansiolle (Android 11+), ja Telegram on suunnitelmissa. Puhelimilla, joiden akunhallinta on aggressiivista, sovelluksen keskeytyksen aikana saapuvat viestit voivat jäädä väliin: akkupoikkeus tekee automaattisesta tilasta luotettavan.

**Miksi ääniviestillä ei ole lähettäjän nimeä?**
Nimi näkyy vain, jos viestin ilmoitus saapui lähettäjän tunnistuksen ollessa käytössä ja sen ilmoittama pituus vastaa ääntä. Käsin ladatut viestit (automaattinen lataus pois käytöstä) ja viestit, joiden ilmoitusta ei koskaan saapunut, jäävät tarkoituksella ilman nimeä: väärä nimi on pahempaa kuin nimen puuttuminen.

**Miksi kauppoja on kaksi (Play ja F-Droid)?**
Sama sovellus, samat ominaisuudet. F-Droid rakentaa sen lähdekoodista ilman suljettuja komponentteja; Play lisää automaattisen kaatumisraportoinnin.

**Onko se todella yksityinen?**
Kyllä. Lähdekoodi on avoin; voit varmistaa, ettei mikään data lähde laitteelta. Katso repositorion tietosuojakäytäntö.

---

Löysitkö virheen tai huomasitko puutteen? Avaa issue [GitHubissa](https://github.com/RisorseArtificiali/anti-vocale/issues).
