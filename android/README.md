# LaundryBooking для Android

## Запуск

Нужны Docker Desktop, Android Studio с Android SDK 36 и JDK 17. Приложение устанавливается на Android 8.0 и новее.

1. Из корня репозитория запустите `docker compose up -d --build db backend`. Готовность API можно проверить на [странице Swagger](http://localhost:8000/docs).
2. Откройте папку `android` в Android Studio. В **Settings → Build, Execution, Deployment → Build Tools → Gradle** выберите JDK 17 в поле **Gradle JDK**. [Настройки JDK в Android Studio](https://developer.android.com/build/jdks#gradle-jdk)
3. Выберите эмулятор и нажмите **Run**. Debug-сборка по умолчанию обращается к `http://10.0.2.2:8000`, адресу компьютера из Android Emulator. [Документация Android Emulator](https://developer.android.com/studio/run/emulator-networking-address)
4. Для сборки APK из PowerShell перейдите в `android` и выполните команду ниже. APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

```powershell
.\gradlew.bat :app:assembleDebug
```

Для телефона в той же Wi-Fi сети задайте IP компьютера через `LAUNDRY_API_URL`. Укажите адрес сервера до `/api`. Порт 8000 должен быть доступен с телефона.

```powershell
.\gradlew.bat :app:assembleDebug "-PLAUNDRY_API_URL=http://192.168.1.50:8000"
```

Для Run из Android Studio то же свойство можно добавить в пользовательский `~/.gradle/gradle.properties`. На macOS и Linux запуск Wrapper выглядит как `bash ./gradlew :app:assembleDebug`. HTTP разрешён в debug-сборке для локального запуска. Для release укажите HTTPS-адрес API.

## Демо с двумя аккаунтами

1. Создайте аккаунт владельца и прачечную. Скопируйте её ID или отправьте через системное меню «Поделиться ID».
2. Выйдите, зарегистрируйте участника, найдите прачечную по ID и подайте заявку.
3. Войдите владельцем. Откройте прачечную, перейдите на вкладку «Заявки» и одобрите участника.
4. Войдите участником, откройте прачечную, выберите день и забронируйте свободный час. Проверьте запись во вкладке «Мои брони» и подтвердите отмену.
5. На вкладке «Расписание» нажмите «Сохранить Excel» и выберите папку.

Для проверки конфликта откройте один и тот же свободный час под двумя аккаунтами. Создайте первую бронь, а затем подтвердите вторую с уже открытого расписания. Второй аккаунт получит сообщение сервера, а слот обновится до «Занято». Для проверки недоступности сервера можно остановить сервис `backend`, нажать «Обновить», затем снова запустить его и повторить запрос.

## GoF-паттерны на защите

| Паттерн | Где реализован | Как объяснить |
| --- | --- | --- |
| Adapter | [LaundryApiAdapter](app/src/main/java/ru/laundrybooking/data/LaundryApiAdapter.kt) реализует [LaundryService](app/src/main/java/ru/laundrybooking/domain/LaundryService.kt). | ViewModel вызывает `book` с выбранным часом. Адаптер отправляет `start` и `end` в формате FastAPI и превращает ответ в `Booking`. Маршруты, JSON-поля и формат времени сосредоточены в слое данных. |
| Observer | [LaundryViewModel](app/src/main/java/ru/laundrybooking/ui/LaundryViewModel.kt) публикует `StateFlow<UiState>`. [LaundryApp](app/src/main/java/ru/laundrybooking/ui/LaundryApp.kt) подписывается через `collectAsStateWithLifecycle`. | Когда сервер подтверждает бронь, ViewModel публикует расписание с новой записью. Compose получает состояние и обновляет строку расписания и список «Мои брони» при их отображении. |

## Автопроверки

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Тесты Android проверяют пересечения записей, границы часовых слотов и фильтрацию своих броней. Проверки сервера запускаются из корня командой `docker compose run --rm --build tests`.

На Android Emulator (API 36) с локальными FastAPI и PostgreSQL проверены сценарий с двумя аккаунтами, вход после перезапуска приложения и сохранение реального файла `.xlsx`. При ответе HTTP 409 расписание обновилось и показало занятую запись. После остановки FastAPI приложение показало ошибку связи, а после запуска сервера повторный запрос загрузил расписание.

Debug APK собрался. Прошли 3 теста Android, `lintDebug` без ошибок и 120 тестов API.
