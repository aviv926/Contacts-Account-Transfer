# Contact Account Transfer

A small Android app for copying, moving, deleting, and managing raw contacts in accounts visible through the Android Contacts Provider.

## Use

1. Install and open the app, then grant read and write contact access.
2. Choose a source account to filter contacts. Select a specific account before using **Delete from account**; deletion is confirmed and scoped to that account.
3. Choose a destination account and select contacts to copy or move. Move asks for confirmation and removes each original only after its copy succeeds.
4. Choose a group in **Group (filter / import / export)** to filter the contact list or use **Import group CSV** / **Export group CSV**. Import and export use the Android document picker. The CSV format has `Name,Phones,Emails` columns; multiple phone numbers and emails are separated by line breaks inside their quoted CSV field. Imported contacts are created in the account that owns the selected group and assigned to that group.

Group memberships are account-specific. Copy/move transfers contact data but does not copy source group references because those references belong to the source account.

The app operates locally through Android's Contacts Provider and does not request Internet access. Contact files are read or written only when you choose a document in the system picker.

## Build

With Android SDK 36, Java 17, and Gradle 8.13 installed, run `gradle assembleDebug`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK on pushes, pull requests, and manual runs. Download the `contact-account-transfer-debug` artifact from the workflow run.
