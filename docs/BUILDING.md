# Сборка и подпись APK

## Требования

- JDK 17.
- Android SDK Platform 34 и Build Tools 34.0.0 или совместимые.
- Интернет при первой сборке для Gradle Wrapper и зависимостей из Google Maven и Maven Central.

Укажите путь к SDK через `ANDROID_HOME` или создайте локальный `local.properties` со строкой `sdk.dir=/absolute/path/to/sdk`. Этот файл исключён из Git. Gradle Wrapper 8.7 находится в репозитории.

```bash
./gradlew assembleDebug lintDebug
```

Отладочный APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Он подписан локальным **отладочным** ключом Android и не предназначен для публичного релиза.
Не оценивайте плавность прокрутки по Debug APK: на RMX5303 он показал намного более долгие кадры, чем Release из тех же исходников. Методика и результаты — в [LIBRARY_PERFORMANCE.md](LIBRARY_PERFORMANCE.md).

## Релизный APK

```bash
./gradlew assembleRelease
```

Gradle создаст `app/build/outputs/apk/release/app-release-unsigned.apk`. Для распространения APK его нужно выровнять и подписать постоянным релизным ключом Android. Ключ и пароль храните вне репозитория и сделайте резервную копию: без того же ключа Android не сможет установить обновление поверх релиза.

Пример для Android Build Tools (значения путей и паролей подставляет владелец ключа):

```bash
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk vako-player-aligned.apk
apksigner sign --ks /secure/path/vako-music-release.p12 --ks-type PKCS12 --ks-key-alias vako-music --out Vako-Player-v0.0.3.apk vako-player-aligned.apk
apksigner verify --verbose Vako-Player-v0.0.3.apk
```

Публичный релиз 0.0.3 использует `versionCode` 21. Обновление 0.0.1 или 0.0.2 поверх установленного приложения возможно при совпадении сертификата; при разных сертификатах нужна переустановка после сохранения данных. Пакет приложения — `ru.rikko.music`.

Для публичных выпусков используется релизный ключ из локального каталога `~/.local/share/vako-build/release-signing/`, который не попадает в Git. Владелец проекта должен отдельно сохранить резервную копию файла ключа и пароля. Публичный отпечаток сертификата записан в [RELEASE_SIGNATURE.md](RELEASE_SIGNATURE.md).

## CI

GitHub Actions выполняет `assembleDebug lintDebug` на каждом pull request и изменении `main`. Это проверка воспроизводимости сборки, а не подтверждение корректности каждого сценария на устройстве. Ключ подписи не хранится в GitHub Actions для этого выпуска.
