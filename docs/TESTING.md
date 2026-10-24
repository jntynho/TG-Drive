# Validation and acceptance

## Automated

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew testReleaseUnitTest lintRelease assembleRelease bundleRelease
./gradlew connectedDebugAndroidTest
```

The connected smoke suite expects an English-language device/emulator. It verifies loading and executing native TDLib without account credentials, the setup UI/privacy dialog, and cancellation of permanent deletion. It does not substitute for authenticated Telegram testing. The release variant is minified; also install a signed release on a physical device before distributing it.

## Authenticated manual acceptance (test account only)

1. Configure a Telegram application and sign in with an existing account. Test invalid code and two-step password errors; confirm correct values are not logged.
2. Upload known-byte text, image, video, empty and larger files. Download each with the document picker and compare SHA-256 hashes. Test Arabic names and folders.
3. Force a network interruption during transfer, restore connectivity and inspect status. After a process kill, reopen, wait for Telegram, refresh, then check Saved Messages before deciding to retry.
4. Rebuild the index after clearing the local index or on another test installation. Verify file IDs, names and nonempty folder membership. Empty folders are deliberately local.
5. Delete a disposable file through the confirmation dialog. Verify its Saved Message is deleted. Delete a different marked message from the official Telegram app and refresh TG Drive.
6. Sign out, sign in to another test account, and confirm there are no files or folder names from the first account. Return to the first account and rebuild its index.
7. Exercise low storage, unavailable document providers, service timeouts and denied notifications. Verify errors do not claim a successful transfer and retry remains possible.
8. Test Arabic RTL, dark theme, large font sizes, API 26 and modern Android devices including a 16 KB page-size device. Confirm release JNI survives R8.

Do not use irreplaceable personal data. Never delete a real user's files for testing. Public release also needs a current Telegram API terms review and Google Play policy/privacy review.
