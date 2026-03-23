# F-Droid inclusion

F-Droid принимает приложения через **Merge Request в GitLab** в репозиторий [**fdroiddata**](https://gitlab.com/fdroid/fdroiddata), а не через Pull Request на GitHub.

## Что сделать

1. **Опубликуйте исходники** на GitHub (или другом поддерживаемом хостинге) и создайте **первый тег** вида `v1.0.0`, совпадающий с полями в `tech.romashov.whitelistcheck.yml` (`commit`, `versionName`, `versionCode`, блок `init`).
2. Откройте `tech.romashov.whitelistcheck.yml` и замените `YOUR_GITHUB_USER/YOUR_REPO_NAME` на реальные **user/org** и **имя репозитория**.
3. При необходимости поправьте `commit`, `versionName`, `versionCode` и команды `init`, чтобы они совпадали с этим тегом и правилом `MAJOR*1000000 + MINOR*1000 + PATCH` (как в вашем CI).
4. Зарегистрируйтесь на GitLab, [**форкните fdroiddata**](https://gitlab.com/fdroid/fdroiddata/-/forks/new), добавьте файл  
   `metadata/tech.romashov.whitelistcheck.yml`  
   (скопируйте содержимое из этого каталога).
5. Запустите пайплайн в форке; при успехе откройте [**Merge Request в fdroiddata**](https://gitlab.com/fdroid/fdroiddata/-/merge_requests) по шаблону репозитория.

Подробности: [Contributing to F-Droid Data](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md), [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/).

Альтернатива для первой заявки: [Request For Packaging (RFP)](https://gitlab.com/fdroid/rfp/-/issues) — issue с описанием приложения.

## Pull Request на GitHub (ваш репозиторий)

В этом репозитории можно оформить обычный **PR**, который добавляет `LICENSE`, `fdroid/` и `fastlane/metadata/` — это не публикует приложение само по себе, но готовит проект к шагу 4 выше.
