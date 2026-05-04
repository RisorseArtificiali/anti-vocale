# Google Play Developer Publishing API Setup Guide

This guide covers configuring the Google Play Developer Publishing API (v3) for the Anti-Vocale Android app (`com.antivocale.app`). Once configured, the service account can be used in CI workflows to upload and release APKs/AABs to Play Store tracks.

**Prerequisites:**
- A Google Play Console developer account with the Anti-Vocale app created
- Access to Google Cloud Console with the same Google account

---

## 1. Create or Select a GCP Project

1. Open the [Google Cloud Console](https://console.cloud.google.com/).
2. Either create a new project or select an existing one.
3. Note the **Project ID** from the project selector at the top of the page.

## 2. Enable the Android Publisher API

1. In Google Cloud Console, navigate to **APIs & Services** > **Library**.
2. Search for **Google Play Android Developer API**.
3. Click it, then click **Enable**.

## 3. Create a Service Account

1. Go to **APIs & Services** > **Credentials**.
2. Click **Create Credentials** > **Service Account**.
3. Set the service account name, for example `anti-vocale-ci`.
4. Click **Create and Continue**, then **Done**.
5. Note the generated service account email:

```
anti-vocale-ci@YOUR-PROJECT-ID.iam.gserviceaccount.com
```

## 4. Generate a JSON Key

1. In **APIs & Services** > **Credentials**, click the service account you just created.
2. Go to the **Keys** tab.
3. Click **Add Key** > **Create new key**.
4. Select **JSON** and click **Create**.
5. A JSON file downloads automatically. Store it securely -- this is the `PLAY_SERVICE_ACCOUNT_JSON` credential.

**Warning:** This file grants access to your Play Console. Never commit it to version control.

## 5. Link the Service Account in Play Console

The Play Console UI changed in 2024 — there is no longer a "Setup > API access" section. Instead, invite the service account as a user:

1. Open the [Google Play Console](https://play.google.com/console/).
2. In the left sidebar, go to **Users and permissions**.
3. Click **Invite new users**.
4. Paste the service account email from the JSON key (the `client_email` field, e.g. `anti-vocale-ci@YOUR-PROJECT-ID.iam.gserviceaccount.com`).
5. Under **App access**, select the Anti-Vocale app (or "All apps").
6. Under **Account permissions**, assign:

| Permission | Scope |
|---|---|
| Release management | All tracks (internal, alpha, beta, production) |
| Store listing | Read and write |
| Presence | Access to Play Console |

7. Set **Access expiry** to **Never**.
8. Click **Invite user** to save.

## 6. Add GitHub Secrets

Go to the GitHub repository **Settings** > **Secrets and variables** > **Actions** and add the following secrets.

### PLAY_SERVICE_ACCOUNT_JSON

Paste the entire contents of the JSON key file downloaded in step 4.

### KEYSTORE_BASE64

Encode your signing keystore as a base64 string:

```bash
base64 -w 0 your-keystore.jks | pbcopy
```

On macOS, `pbcopy` places the result in the clipboard. On Linux, pipe to `xclip` or redirect to a file:

```bash
base64 -w 0 your-keystore.jks > keystore_b64.txt
```

### KEYSTORE_PROPERTIES

Paste the keystore properties as a single string. The format should match what `app/keystore.properties` expects:

```
storeFile=/path/to/keystore.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Store the entire block as one GitHub secret value.

## 7. Verify Access

Install the Google API client library and run the verification script:

```bash
pip install google-api-python-client
python3 scripts/verify-play-api.py path/to/service-account.json
```

For machine-readable output (e.g., in CI):

```bash
python3 scripts/verify-play-api.py --json key.json
```

If access is configured correctly, you will see output like:

```
Tracks for com.antivocale.app:
  Track: production
    Release: status=completed versions=[15]
  Track: internal
```

---

## Troubleshooting

**"The project is not linked to the Google Play Developer Console"**

Ensure the Google Play Android Developer API is enabled in your GCP project (step 2) and that the service account email has been invited via Play Console > Users and permissions (step 5).

**"Permission denied" on API calls**

Confirm the service account has been invited in Play Console > Users and permissions (step 5). Check that the correct permissions are assigned and that the package name matches `com.antivocale.app`.

**403 Forbidden after setting up permissions**

Google Play permissions can take up to 24 hours to propagate. Wait and retry. If the error persists, remove and re-invite the service account in Play Console > Users and permissions.
