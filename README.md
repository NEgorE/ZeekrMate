# ZeekrMate

Android-приложение для центрального экрана **Zeekr 7X**.

## Экран

- Автомобиль: Zeekr 7X
- Дисплей: 16 дюймов, 3.5K Mini-LED
- Ориентация: альбомная

## Сейчас

При запуске открывается одна страница — этот README.

## Стек

- Kotlin
- Android 12+ (API 31)
- Gradle

## Сборка

Нужны Android Studio (или Android SDK) и JDK 17+.

```text
gradlew.bat :app:assembleDebug
```

APK появится здесь: `app/build/outputs/apk/debug/app-debug.apk`

На эмуляторе удобнее альбомный планшет с большой диагональю — так ближе к 16" экрану машины.

Текст на экране берётся из `app/src/main/assets/README.md`. Если правите README — обновите оба файла.
