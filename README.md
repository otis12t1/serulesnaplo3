# Sérülésnapló (Android)

Rendszám élő felismerése (ML Kit) → sérülésfotók → megjegyzés (diktálással) → mentés → Excel (.xlsx) export.

## APK készítése GitHubon
1. Hozz létre egy új, üres GitHub tárhelyet (repository), ágnév: `main`.
2. Töltsd fel ennek a mappának a teljes tartalmát (a rejtett `.github` mappával együtt).
3. GitHub → **Actions** fül → „Build APK" → lefutása után (kb. 5–8 perc) az oldal alján az **Artifacts** között töltsd le a `serulesnaplo-apk` fájlt, csomagold ki.
4. Másold át a telefonra az `app-debug.apk` fájlt, nyisd meg, engedélyezd az ismeretlen forrásból telepítést.

Fájlok helye a telefonon:
- Fotók: Képek/serulesek/<RENDSZÁM>/<ÉÉÉÉ-HH-NN>/
- Excel: Letöltések/serulesek/
