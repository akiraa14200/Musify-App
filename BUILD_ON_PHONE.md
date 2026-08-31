# Musify — phone-only GitHub build guide

You can build Musify without Android Studio. The ZIP contains the complete source, your new logo, the Musify branding, the Gradle wrapper, and a GitHub Actions workflow.

## What is inside
- App name: **Musify**
- Application ID: `com.musify.music`
- Version: `1.0.0`
- Minimum Android: API 26 (Android 8.0)
- Target Android: API 36
- Universal debug APK workflow included
- Your supplied logo is used for the launcher/app artwork

## Method A — easiest if you are using only a phone

### 1. Create a GitHub repository
1. Open GitHub in Chrome.
2. Create a new repository named `Musify`. GitHub's repository page lets you choose the repository name and visibility.
3. Leave the repository otherwise empty if possible.

### 2. Upload the ZIP
1. Open your new `Musify` repository.
2. Tap **Add file**.
3. Tap **Upload files**.
4. Select `Musify-GitHub.zip`.
5. Tap **Commit changes**.

GitHub's web uploader supports file uploads and commits them to the repository.

### 3. Open Codespaces
1. On the repository page, tap **Code**.
2. Tap **Codespaces**.
3. Tap **Create codespace on main**.
4. Wait for the Codespace editor to load.

The repository is not empty because the ZIP file is already committed, so this avoids the “Codespaces cannot be created on empty repositories” error.

### 4. Open the terminal
In Codespaces, open **Terminal**. If you do not see it, use the top-left menu and choose **Terminal → New Terminal**.

### 5. Extract the project into the repository
Paste these commands **one at a time**:

```bash
unzip -q Musify-GitHub.zip -d musify_project
```

Then:

```bash
cp -a musify_project/. .
```

Then:

```bash
rm -rf musify_project Musify-GitHub.zip
```

### 6. Commit the real project files
Paste:

```bash
git add .
```

Then:

```bash
git commit -m "Add Musify project"
```

Then:

```bash
git push
```

### 7. Let GitHub build the APK
The ZIP contains `.github/workflows/build-apk.yml`. After `git push`, GitHub Actions should start automatically.

1. Go back to your repository page.
2. Tap **Actions**.
3. Tap **Build Musify APK**.
4. Open the newest workflow run.
5. Wait for the green check mark.
6. Scroll down to **Artifacts**.
7. Tap **Musify-universal-debug**.
8. GitHub downloads an artifact ZIP.
9. Extract that ZIP.
10. Inside it you will find `app-universal-debug.apk`.
11. Tap the APK and install it.

GitHub documents that workflow artifacts can be downloaded from the workflow run's **Artifacts** section.

## If Actions does not start automatically
Open **Actions → Build Musify APK → Run workflow**. Choose the `main` branch and start it. The workflow also has `workflow_dispatch`, so it can be started manually.

## Manual build inside Codespaces
If you want to build directly instead of waiting for Actions, open the Codespaces terminal and run:

```bash
chmod +x gradlew
./gradlew :app:assembleUniversalDebug
```

The APK will be at:

`app/build/outputs/apk/universal/debug/app-universal-debug.apk`

## Important
- **Do not upload only the ZIP and expect GitHub to build it.** The source files must be extracted into the repository root. Method A above does this for you.
- Use the **Universal debug APK** first. It is automatically signed for testing and is the least complicated option for a beginner.
- The project uses Gradle 9.3.1 because AGP 9.1.1 requires Gradle 9.3.1.
- A full release APK is not configured with your personal signing key, so do not start with the release build.
- The application ID is changed to `com.musify.music`, so Musify is a separate app from the original `com.nikhil.yt` application.
- Internal Kotlin package names were intentionally left unchanged to reduce build risk.
- The source contains online-service integrations, so the app needs internet access for online music features.
