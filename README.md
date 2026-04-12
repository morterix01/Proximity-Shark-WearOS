# ⌚ Proximity Shark - Wear OS Companion

Questa è la Companion App ufficiale per **Proximity Shark**, progettata per estendere il controllo dei tuoi payload DuckyScript direttamente sul tuo smartwatch Wear OS (testato su Galaxy Watch 4).

## ✨ Funzionalità

- 📁 **Navigazione Remota**: Sfoglia l'intera libreria script organizzata in cartelle sul tuo telefono.
- 🚀 **Remote Trigger**: Avvia l'esecuzione dei payload sul PC target con un semplice tocco dall'orologio.
- ⌨️ **Layout Switcher**: Cambia il layout della tastiera (PC IT, US, Android IT) direttamente dal polso.
- 📊 **Feedback Immediato**: Overlay a tutto schermo (✅/❌) che conferma l'esito dell'invio del payload.
- 🦈 **Design Shark**: UI ottimizzata in Wear Compose con tema coordinato "Dark Shark".

## 🛠️ Come Compilare

Questo progetto è un modulo Android NativoStandalone. È possibile compilarlo tramite Gradle o tramite la pipeline di **Codemagic** già configurata.

```bash
git clone https://github.com/morterix01/Proximity-Shark-WearOS.git
cd Proximity-Shark-WearOS
chmod +x gradlew
./gradlew :app:assembleRelease
```

## 🔗 Collegamento con l'App Principale

Per funzionare correttamente, questa app richiede che l'app principale sia installata sul telefono:
👉 [Proximity Shark (Main Project)](https://github.com/morterix01/Proximity-Shark)

> [!IMPORTANT]
> Entrambe le app devono avere lo stesso `applicationId` (`com.luis.ducky_android`) per permettere la sincronizzazione dei dati tramite le Wearable API di Google Play Services.
