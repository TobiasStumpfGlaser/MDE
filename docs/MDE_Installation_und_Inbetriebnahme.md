# MDE — Installation, Voraussetzungen & Inbetriebnahme (APK)

**Dokument:** Allgemeingültige Installations- und Inbetriebnahmeanleitung  
**Produkt:** MDE (Android App)  
**Verteilung:** APK (intern/inhouse)

---

## 1. Zweck
Diese Anleitung beschreibt die Voraussetzungen und die Schritte, um die Android-App **MDE** intern bereitzustellen, zu installieren und erstmalig in Betrieb zu nehmen.

---

## 2. Voraussetzungen

### 2.1 Endgerät
- **Android 7.0 oder höher**
- **Netzwerkzugang** (WLAN/Mobilfunk/VPN je nach Umgebung)
- **Kamera** (nur erforderlich, wenn scan-/kamerabasierte Funktionen genutzt werden)

### 2.2 Berechtigungen
Die App kann folgende Berechtigungen verwenden:
- **Netzwerkzugriff (Internet)** für die Kommunikation mit einem Server
- **Kamera** für scan-/kamerabasierte Funktionen

---

## 3. Installation (APK)

### 3.1 Benötigte Datei
- Bereitgestellte **MDE APK-Datei**

### 3.2 Manuelle Installation auf dem Gerät
Je nach Gerätepolitik kann die Installation aus „unbekannten Quellen“ eingeschränkt sein.

Vorgehen:
1. APK auf das Gerät übertragen (Download, Dateiablage, USB, etc.)
2. APK öffnen
3. Installation bestätigen
4. App starten

### 3.3 Installation per ADB (optional, IT/Technik)
Voraussetzungen:
- Android Platform Tools (ADB) am PC
- USB-Debugging am Gerät aktiviert

Befehl:
```bash
adb install -r <pfad-zur-apk>
```

---

## 4. Inbetriebnahme (Erstkonfiguration)

### 4.1 Ziel der Erstkonfiguration
Damit die App produktiv genutzt werden kann, müssen **Serverparameter** gesetzt werden. Diese Werte sind umgebungsabhängig und werden intern festgelegt.

### 4.2 Erforderliche Parameter
- **Server-IP oder Hostname**
- **Server-Port**
- **Timeout**

### 4.3 Funktionsprüfung nach der Erstkonfiguration (Kurztest)
Nach der Konfiguration:
1. App starten
2. Login-Bildschirm öffnen
3. Prüfen, ob die notwendigen Daten geladen werden
4. Test-Login durchführen (gültige Zugangsdaten)

Wenn der Kurztest fehlschlägt:
- Parameter (IP/Hostname, Port, Timeout) prüfen
- Netzwerk/VPN prüfen
- Firewall-/Portfreigaben prüfen

---

## 5. Betriebshinweise (empfohlen)

### 5.1 Stabilität im Tagesbetrieb
- Energiesparmodi prüfen (können Netzwerkverhalten beeinflussen)
- WLAN-Roaming/Abdeckung sicherstellen (insb. in Lager-/Hallenbereichen)
- Bildschirm-Timeout sinnvoll konfigurieren (Arbeitsabläufe)

### 5.2 Scan-/Kamerafunktionen (falls genutzt)
- Kamera-Berechtigung zulassen
- Auf ausreichende Beleuchtung und Fokus achten
- Bei Problemen: Test auf einem zweiten Gerät durchführen (Hardware-/Kamera-Varianz)

---

## 6. Fehlerdiagnose (Kurzleitfaden)

### 6.1 Typische Ursachen
- falsche Serverparameter (IP/Hostname, Port, Timeout)
- Gerät nicht im Zielnetz / VPN nicht aktiv
- Port durch Firewall blockiert
- instabile Verbindung (WLAN-Abdeckung, Roaming, Energiesparmodus)

### 6.2 Informationen für interne Supportfälle
Für schnelle Analyse bitte dokumentieren:
- Gerätehersteller und Modell
- Android-Version
- App-Version
- Netzwerktyp (WLAN/Mobilfunk/VPN)
- Zeitpunkt und Ablaufbeschreibung
- Anzeige von Fehlermeldungen / Screenshots

---

## 7. Empfohlene interne Abnahme-Checkliste (Minimal)
1. APK installiert erfolgreich
2. App startet ohne Fehlermeldung
3. Serverparameter gesetzt
4. Daten werden geladen (Login-Vorprüfung)
5. Test-Login möglich
6. (optional) Scan-Test erfolgreich
