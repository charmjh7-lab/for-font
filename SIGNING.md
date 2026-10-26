# Fixed release signing

- Package: `com.forfont.studio`
- Alias: `forfont` / RSA 3072 / PKCS12
- Certificate SHA-256: `80400d4854a78108b4be90802ea183d23f659deebe1e20a4340cf7b7e927478a`
- Original private backup: **ForFont-signing-backup.zip** in the user's ChatGPT files.

Always recover that backup and reuse its key. Never generate a replacement key for an update. Do not commit the backup, key, or password to this public repository. The signing helper verifies the exact certificate before and after signing.

```sh
./gradlew assembleRelease
python3 tools/sign_release.py app/build/outputs/apk/release/app-release-unsigned.apk ForFont.apk --backup /private/ForFont-signing-backup.zip --sdk "$ANDROID_HOME"
```

GitHub Actions builds an unsigned release artifact. Sign it with the same private backup before distribution. Increase versionCode and versionName for later updates.
