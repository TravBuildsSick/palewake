# Palewake

A self-hosted app catalog for Android. Palewake reads `catalog.json` from this repo, checks each
listed app's latest GitHub release for updates, and lets you install/update straight from the
app — no Play Store, no server.

## Installing it the first time

Palewake can't distribute its own first install — that one step has to be manual:

1. Open this repo's [Releases page](https://github.com/TravBuildsSick/palewake/releases) on your
   phone and download the latest `.apk`.
2. Tap the downloaded file to install. Android (via Google Play Protect) will likely block it as
   an unrecognized app the first time — either:
   - **Play Store app → profile icon → Play Protect → gear icon → toggle off "Scan apps with
     Play Protect"**, then retry the install (you can turn it back on after), or
   - allow install from whichever app you downloaded it with (browser, file manager) when
     prompted.

After that, updates to Palewake itself — and to any app listed in its catalog — happen through
the app: tap "Update" on a card, or pull down to refresh the catalog.

## How the catalog works

`catalog.json` at the repo root lists apps as `{id, name, description, package_name, repo}` —
just a pointer to each app's own GitHub repo, nothing version-specific. On refresh, the client
hits `api.github.com/repos/<repo>/releases/latest` for every entry concurrently; whichever
release tag (`v<versionCode>`) and `.apk` asset comes back is what's offered. An app with no
release yet, or a request that fails, is just skipped — not an error.

Adding an app to the catalog is a one-line addition to `catalog.json`. Shipping a new version of
an already-listed app never touches this repo — it only needs a new tagged release on that app's
own repo.

## Adding a new app to the catalog

```json
{
  "id": "your-app-id",
  "name": "Display Name",
  "description": "One line.",
  "package_name": "your.app.package",
  "repo": "TravBuildsSick/your-repo"
}
```

The other repo needs its own `push_update.sh` (build a signed release APK, `gh release create
v<versionCode> <apk>`) — see this repo's `push_update.sh` as the template.
