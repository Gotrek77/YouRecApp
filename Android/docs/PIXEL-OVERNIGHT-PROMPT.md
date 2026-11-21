# Prompt per una sessione notturna di test sul Pixel

Come usarlo, dalla radice del clone sul PC (bash o fish), con il Pixel collegato
via USB, debug USB attivo e il PC autorizzato (`adb devices` lo mostra come
`device`):

```sh
git fetch origin && git checkout ccr-36384783-fh3rat && git pull
sed -n '/^---8<---$/,$p' Android/docs/PIXEL-OVERNIGHT-PROMPT.md | tail -n +2 | \
  claude -p --allowedTools "Bash(adb *)" "Bash(python3 *)" "Bash(ffprobe *)" "Bash(ffmpeg *)" \
  "Bash(./gradlew *)" "Bash(git log *)" "Bash(git rev-parse *)" "Bash(git status *)" "Bash(gh run *)" \
  "Bash(mkdir *)" "Bash(ls *)" "Bash(sha256sum *)" "Bash(sleep *)" "Bash(tar *)" "Bash(cat *)" "Bash(cp *)" \
  "Read" "Write" "Glob" "Grep" 2>&1 | tee pixel-run.log
```

Prima di lanciarlo, decidi la riga `DATI_SACRIFICABILI` qui sotto: con `no`
(predefinito) la sessione non disinstalla mai l'app e salta i test che lo fanno
se sul telefono ci sono già progetti YouRec.

---8<---
Sei in una sessione autonoma e non presidiata, di notte. Il Pixel è collegato via
USB a questo PC. Il tuo compito: eseguire sul telefono tutti i test sul
dispositivo del port Android di YouRec che si possono fare senza una persona, e
lasciare un rapporto completo e onesto. Scrivi il rapporto in italiano.

DATI_SACRIFICABILI: no
(se `sì`: i progetti YouRec già presenti sul telefono possono andare persi;
se `no`: vanno preservati a ogni costo.)

## Contesto da leggere prima di fare qualsiasi cosa
- `AGENTS.md`, `docs/CAMERA-QA.md` (processo di accettazione della camera
  dell'app iPhone: le impostazioni richieste e le callback riuscite non sono
  prove; lo sono l'anteprima visibile e il file salvato).
- `Android/README.md` (cosa è portato e cosa no) e soprattutto
  `Android/docs/DEVICE-QA.md`: è la lista dei controlli. Segui le sue sezioni
  0–4 e la sezione "Device artifact cleanup".
- `Android/scripts/emulator-smoke.py`: guida l'app con uiautomator; funziona
  anche su un telefono, ma **disinstalla l'app** all'inizio.

## Regole assolute
1. Non modificare file del repository, non fare commit né push. Scrivi solo
   dentro `Android/qa-runs/<AAAAMMGG-hhmm>/` (crea la cartella), che non va
   committata.
2. Non usare mai `adb shell pm clear`, `adb uninstall` (salvo regola 4),
   `rm` dentro `Projects/` o `RecentlyDeleted/` dell'app, né factory reset.
   Installa sempre sul posto: `adb install -r`.
3. Prima di tutto, fotografa lo stato: `adb shell ls -lR
   /sdcard/Android/data/com.antirez.yourec/files` salvato nel rapporto, e copia
   l'intera cartella sul PC (`adb pull /sdcard/Android/data/com.antirez.yourec
   <run>/backup-esterno`), più i file interni se l'app installata è debug
   (`adb shell run-as com.antirez.yourec tar cf - files | cat > <run>/backup-interno.tar`).
   Calcola gli sha256 dei file in `Projects/` prima e dopo la sessione e
   confrontali: i progetti preesistenti devono risultare identici.
4. Se `DATI_SACRIFICABILI` è `no` e in `Projects/` c'è almeno un progetto, NON
   eseguire `emulator-smoke.py` (disinstalla) e non fare nulla che disinstalli;
   fai i test manuali via adb/uiautomator in un progetto di test nuovo. Se è
   `sì` o non ci sono progetti, puoi eseguire lo smoke test.
5. Le registrazioni di test restano in progetti di test, mai dentro progetti
   esistenti. Alla fine non cancellarle: elencale nel rapporto.
6. Se il telefono si scollega, la batteria scende sotto il 20% o
   `adb shell dumpsys thermalservice` indica stato ≥ SEVERE, metti in pausa
   (attendi 10 minuti, al massimo 3 volte), poi chiudi il rapporto con ciò che
   hai. Non ripetere all'infinito un passo che fallisce: due tentativi, poi
   annota e vai avanti.
7. Distingui sempre nel rapporto: osservazione (con il file che la prova),
   ipotesi, dubbio residuo. Un passo non eseguito non è "superato".
8. Tieni lo schermo acceso durante la sessione (`adb shell svc power stayon
   usb`) e ripristina alla fine (`adb shell svc power stayon false`).

## Passi
0. Identità: `adb devices -l`, modello, versione Android
   (`getprop ro.build.version.release`, `ro.build.fingerprint`), commit
   (`git rev-parse HEAD`). Annota tutto.
1. APK: prova `cd Android && ./gradlew --no-daemon assembleDebug`. Se manca
   l'SDK o il JDK 17, scarica l'artefatto `YouRec-debug-apk` dell'ultima run
   riuscita del workflow `Android` per lo stesso commit (`gh run list --branch
   ccr-36384783-fh3rat`, `gh run download <id> -n YouRec-debug-apk`). Verifica
   che il commit della run coincida con `HEAD`; se non coincide, annotalo.
2. Stato e backup come da regola 3. Installa con `adb install -r`, concedi
   CAMERA e RECORD_AUDIO con `pm grant`.
3. Se consentito dalla regola 4: `python3 Android/scripts/emulator-smoke.py
   <apk> <run>/smoke`. Leggi `report`/`app.log`/`logcat.txt` e guarda gli
   screenshot (usa Read sulle PNG). Riporta ogni FAIL con la causa.
4. Controlli di `DEVICE-QA.md` fattibili senza persona, ciascuno con prove:
   - Lunghezza tracce video/audio uguali (ffprobe) in una ripresa a 1080p e una
     a 4K; nessun warning `YouRec` in `adb logcat -s YouRec`.
   - Ogni camera elencata si apre: screenshot dell'anteprima per ciascuna,
     e una breve ripresa per ciascuna; confronta il primo fotogramma del file
     (ffmpeg) con lo screenshot: stessa inquadratura, non stirata, dritta,
     forma 1080×1920 o 1920×1080 (e 4K). Camera frontale: anteprima
     specchiata, file non specchiato (serve testo leggibile nella scena: se
     non c'è, segna "non verificabile").
   - Registra → Pausa → Riprendi → Stop: un file per segmento, tempo in pausa
     assente.
   - Cambio camera durante la ripresa: un solo take, suono continuo.
   - Ripresa lunga 60 minuti a 1080p (temperatura e fotogrammi persi annotati
     ogni 10 minuti con `dumpsys thermalservice` e la cadenza dei timestamp
     del file).
   - Durabilità: continua dopo riavvio (force-stop e riapertura), kill durante
     una ripresa (`am force-stop` a metà ripresa: al riavvio il take torna),
     Undo/Redo dopo riavvio, Duplicate e Back up.
   - Editor: anteprima, split, trim, transizione dissolvenza, testo,
     immagine, copertina, pause, censura, Enhance audio (RNNoise e
     DeepFilterNet3), con export verificato da ffprobe e fotogrammi estratti.
   - Effetti video: per ogni effetto offerto nel pannello Effects, un progetto
     di test con quell'effetto su tutta la clip; screenshot dell'anteprima e
     export; estrai lo stesso istante dall'export e confrontalo a vista con
     l'anteprima; verifica che il fotogramma cambi rispetto al take originale
     e che non ci siano crash o shader non compilati nel log. Annota il tempo
     di export rispetto alla durata.
   - Sottotitoli: se c'è Wi‑Fi, scarica il modello (circa 1,9 GB) e trascrivi
     un take con parlato (se nessun take ha parlato, crea un video di prova con
     ffmpeg da `Android/ThirdParty/qwen-asr/samples/jfk.wav`, copialo nel
     telefono e importalo dall'editor; se l'import tramite selettore di
     sistema non è automatizzabile, segna "non eseguito" e perché).
   - Export: H.264 e H.265; 720p, 1080p, 4K; 24/30/60 fps: dimensione, fps e
     numero di fotogrammi da ffprobe; nessun fotogramma duplicato o perso.
   - Sorgenti intatte: sha256 dei take prima e dopo modifiche ed export.
5. Non verificabili senza una persona (segnali come tali, non come superati):
   rotazione fisica del telefono, applauso per la sincronia, microfoni
   cablati/Bluetooth, chiamate, memoria piena, TalkBack, giudizio sulla
   naturalezza della voce dopo Enhance.

## Rapporto
`Android/qa-runs/<run>/RAPPORTO.md`, in italiano: intestazione con telefono,
Android, commit, APK usato, ora di inizio e fine; una tabella per sezione di
`DEVICE-QA.md` con esito (superato / fallito / non verificabile / non
eseguito), prova (percorso del file) e note; poi i problemi trovati in ordine
di gravità, ciascuno con i passi per riprodurlo e i righi di log rilevanti; in
fondo il confronto sha256 dei progetti preesistenti (devono essere identici) e
l'elenco dei progetti di test lasciati sul telefono. Se lo stesso controllo ha
dato esiti diversi in due tentativi, riportali entrambi.
