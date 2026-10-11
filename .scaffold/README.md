# Scaffold

This project is based on [Scaffold v0.2](https://github.com/Julius-Babies/Scaffold/tree/v0.2).
Files derived from it: `.github/`.

## Parameters

The values the Scaffold components ask the project to fill in.

| Component | Parameter | Value |
| --- | --- | --- |
| `github` | `APP_NAME` | `Overmail` |
| `github` | `DOCKER_IMAGE` | `ghcr.io/overmail/overmail` |
| `github` | Server JDK | `26` |
| `github` | Android JDK | `21` |

## Deviations

Everything else that differs from Scaffold v0.2. *Upstream* marks deviations that
would help every project and should move to Scaffold.

| File | Deviation | Reason | Upstream |
| --- | --- | --- | --- |
| `.github/actions/setup-android-project/action.yaml` | `google_services_file` input, restored to `app/android/google-services.json` | the app uses Firebase Cloud Messaging and the file is not committed | – |
| `.github/workflows/deploy.yaml` | passes the `GOOGLE_SERVICES_FILE` secret to the setup action | same | – |
