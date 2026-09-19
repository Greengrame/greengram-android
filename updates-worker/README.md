# Update Changelog

Edit `changelog.xml` before publishing a new beta build. The first RSS `<item>` is used for the update dialog:

- `<title>` becomes the update dialog title.
- `<description>` becomes the release notes text.

Run `publishDebugUpdate` after changing the item for LibreTGB, or `publishReleaseUpdate` for LibreTG. The task uploads the matching APK, manifest, and `changelog.xml` to the `tg-updates` R2 bucket, then deploys the Worker.

The app sends its package name and `Accept-Language` header. The Worker uses the beta or release manifest and selects the matching `<libre:lang>` RSS item.
