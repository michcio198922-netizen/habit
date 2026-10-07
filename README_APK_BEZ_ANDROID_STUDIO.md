# Jak zrobić APK bez Android Studio

Najprościej przez GitHub Actions — wszystko kompiluje się w chmurze.

1. Wejdź na GitHub i utwórz nowe puste repozytorium, np. `nawyki-android`.
2. Wgraj do niego **zawartość** folderu `NawykiAndroid` (nie sam folder jako jeden plik ZIP).
3. Otwórz zakładkę **Actions**.
4. Wybierz workflow **Zbuduj APK**.
5. Kliknij **Run workflow** → **Run workflow**.
6. Po zakończeniu wejdź w wykonany workflow.
7. Na dole strony w sekcji **Artifacts** pobierz `Nawyki-APK`.
8. Rozpakuj pobrany plik ZIP — w środku będzie `app-debug.apk`.
9. Przenieś `app-debug.apk` na Samsunga i otwórz go.
10. Jeśli telefon zapyta, zezwól aplikacji Pliki/Chrome na **Instalowanie nieznanych aplikacji** i wybierz **Zainstaluj**.

## Ważne
- To jest APK typu **debug**, dobre do własnego używania i testów.
- Dane aplikacji są zapisywane lokalnie w telefonie.
- Minimalny Android: 8.0 (API 26).
