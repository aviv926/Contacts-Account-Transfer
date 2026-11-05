# Contact Account Transfer

A small Android app for copying or moving raw contacts between accounts visible in the Android Contacts Provider. It is intended for cases such as moving contacts saved on the device into a Google account synced through DAVx⁵.

## Use

1. Install and open the app on the device.
2. Grant read and write access to contacts.
3. Choose a source in **Show contacts from** to filter the list to one account, or leave **All accounts** selected.
4. Choose a destination account. Accounts are listed when they already contain at least one contact.
5. Select the visible contacts and choose **Copy selected** or **Move selected**. **Select all** applies only to the current source filter.

Move asks for confirmation. Each selected contact is copied in a single Contacts Provider batch; for a move, deleting the source is part of that same batch. If that contact's copy fails, its source remains. Contacts in the same account as the destination are skipped.

The app operates locally through Android's Contacts Provider and does not request Internet access. Data fields and photos are copied when exposed by the provider. Account-specific group memberships are skipped because their group references belong to the source account.

## Build

With Android SDK 36, Java 17, and Gradle 8.13 installed, run `gradle assembleDebug`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK on pushes, pull requests, and manual runs. Download the `contact-account-transfer-debug` artifact from the workflow run.

