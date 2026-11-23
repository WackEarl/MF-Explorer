# MF Explorer test plan

Fixed checklist for release candidates of the [WackEarl/MF-Explorer](https://github.com/WackEarl/MF-Explorer) fork. New or changed behavior is tested in addition to this smoke suite, not instead of it.

## How to use

For each release candidate:

1. Gary publishes the RC and posts a short test request with a link to this file, plus **only** the new or changed points for that version (`R1`, `R2`, …). The fixed smoke suite below is always in scope and is not repeated in the request unless a step itself changed.
2. Cody runs the fixed smoke suite and the new points on the environment below.
3. Cody posts the result as a comment on the GitHub **release issue**, using the [template](#result-template): Pass or Fail per point, version, device or AVD, and date.
4. Cody also stores the artifacts on botshare under `tests/MF-Explorer/<VERSION>/` (see [Artifacts](#artifacts)).

`<VERSION>` is the exact `versionName`, for example `2.1.8-rc1` or `2.1.8`.

## Environment

- **Device:** Unraid emulator AVD `nexus_4_14.0` (API 34). Homey starts and stops the emulator and agent-device tooling on request.
- **Share:** NFS share **mfx-nfstest** only. Do not mount or open any other share.
- **Install:** agent-device with `appPath=/apk/<file>.apk`. Verify the sha256 of the APK against the GitHub release before installing.
- **Update, not clean:** unless the test request says otherwise, install over the previous release or RC. After the update, check that tabs, NFS connections and folder settings are still there (S2).
- **Safety:** do not use or mention crypt paths. Do not record secrets, passwords, keystores or tokens in results. In comments and artifacts, name the share as `mfx-nfstest`; do not add host IPs or extra paths.

A fixed, script-resettable dataset on mfx-nfstest is **out of scope** for this plan (tracked separately). Use the current contents of the share, and clean up what you create (see [Cleanup](#cleanup)).

GitHub Actions emulator jobs, Firebase Test Lab, and features inherited from upstream that this fork does not change (archives, FTP/SFTP/SMB/WebDAV, root, Shizuku) are also out of scope unless a release touches them. Cover those only as new `R` points.

## Fixed smoke suite

| ID | What to check | Pass when |
|----|---------------|-----------|
| S1 | App start | The app opens to a usable file list, with no crash or ANR. About shows the expected `versionName` and `versionCode`. |
| S2 | Update keeps state | After an update-install, previous tabs, the NFS connection and folder view/sort settings are still there. |
| S3 | Local listing | A local folder (for example the usual on-device test folder) lists without error. |
| S4 | NFS listing | The mfx-nfstest root lists (folders and files appear) within a few seconds. A loading indicator is acceptable. |
| S5 | Breadcrumb, including reload of a failed folder | Navigating with breadcrumbs works. To test reload: open an NFS folder, turn on airplane mode, pull to refresh until the list shows Error; turn airplane mode off; tap the **current** (selected) breadcrumb segment. The folder must reload (Loading, then the list within about 4 s). |
| S6 | Tabs and plus button | Existing tabs switch correctly. The last tab stays visible beside "+" (not covered). A new tab opened with "+" is scrolled into view. |
| S7 | Search, query kept after back | Start a search and type a query that returns hits. Hide the keyboard with back: the query and results stay, and the toolbar subtitle still shows the term. The next back, or clearing the query, leaves search and restores the unfiltered list. |
| S8 | New folder scrolls into view | Create a folder with "New folder". If "Folders first" would keep it on screen anyway, turn that off first so the new name would otherwise land off-screen. The new folder is scrolled into view. |
| S9 | Rename | Rename a test file or folder from the row menu (on mfx-nfstest or locally). The new name is shown, with no crash. |
| S10 | Copy local→NFS and NFS→NFS | Copy a small set of files both ways. Both copies succeed. Note the duration and approximate size in the result, for later speed comparison. Clean up afterwards. |
| S11 | Move NFS→local | Cut a test file on mfx-nfstest and paste it into a local folder. The file arrives and the source is gone. A log line about `AtomicMoveNotSupportedException` during the copy-and-delete fallback is a known cosmetic issue: do not fail the point; mention it under notes if seen. |
| S12 | Delete, online and offline | Online: delete a test file, and it disappears. Offline (airplane mode): delete a test file on mfx-nfstest; an error is shown (toast or dialog, for example that the NFS server is unreachable), the file stays, and there is no crash. Capture logcat for the offline case. |
| S13 | Thumbnails while scrolling | In a folder with images and videos on mfx-nfstest, scroll the list or grid. Thumbnails for both appear, and scrolling stays usable (no long freeze). |
| S14 | Sort and view per folder | Set folder A to a non-default view/sort (for example grid) and leave folder B on the default. The settings survive folder switches and an app restart. "Reset folder view and sort" restores the default for that folder only. "Use as default for all folders" applies to other folders. Reset afterwards so the share is left as you found it. Exclusion of these settings from Android backup is a code/privacy check, not a device check, unless the test request asks for it. |
| S15 | Rotation and back | Rotate the device in a folder list and in search. A back press right after rotation is not lost. The current folder, and an active search if any, is preserved. |
| S16 | Network loss message | With an NFS folder open, enable airplane mode. A readable error is shown (no raw RPC or Java class names). After airplane mode is turned off, NFS works again without an app restart (list or retry). When there is no network, the failure should be fast (around a second, not tens of seconds). |
| S17 | No crash | Through the whole run: no process crash, no forced close, no stuck ANR. |
| S18 | Logcat: no duplicate stack traces | For a failed folder listing, the log shows one stack trace per error, not a repeat when the same cached failure is shown again. Export logcat via android-log and keep the export path in the result. |

## Release-specific points

Gary lists only new or changed checks as `R1`, `R2`, … in the RC test request, with a link to this file. Cody runs them in addition to S1–S18 and records them in the same table.

## Result template

Post this as a comment on the GitHub release issue, and store the same text as `RESULTS.md` on botshare:

```markdown
## Test result: <versionName> (<versionCode>)

- Date: YYYY-MM-DD (Europe/Berlin)
- Tester: Cody
- Device: AVD nexus_4_14.0 (API 34)
- Install: update from <previous version> via `appPath=/apk/<file>.apk` (or clean, if requested)
- sha256: matches the GitHub release / mismatch
- Artifacts: botshare `tests/MF-Explorer/<VERSION>/`

| ID | Point | Result | Notes |
|----|-------|--------|-------|
| S1 | App start | Pass / Fail | |
| S2 | Update keeps state | Pass / Fail | |
| S3 | Local listing | Pass / Fail | |
| S4 | NFS listing | Pass / Fail | |
| S5 | Breadcrumb, including failed-folder reload | Pass / Fail | |
| S6 | Tabs and plus button | Pass / Fail | |
| S7 | Search, query kept after back | Pass / Fail | |
| S8 | New folder scrolls into view | Pass / Fail | |
| S9 | Rename | Pass / Fail | |
| S10 | Copy local→NFS and NFS→NFS | Pass / Fail | duration and size: |
| S11 | Move NFS→local | Pass / Fail | |
| S12 | Delete, online and offline | Pass / Fail | |
| S13 | Thumbnails while scrolling | Pass / Fail | |
| S14 | Sort and view per folder | Pass / Fail | |
| S15 | Rotation and back | Pass / Fail | |
| S16 | Network loss message | Pass / Fail | |
| S17 | No crash | Pass / Fail | |
| S18 | Logcat: no duplicate stack traces | Pass / Fail | export: |
| R1 | <new point from the test request> | Pass / Fail | |

### Findings

- Severity (critical / annoying / cosmetic): where, steps to reproduce, expected vs actual, screenshot or log, suggested fix.

### Cleanup

What was deleted or reset. Airplane mode off. Emulator and containers left as agreed.
```

## Artifacts

On botshare:

```
tests/MF-Explorer/<VERSION>/
  RESULTS.md          # same content as the issue comment
  screenshots/        # or PNG files next to RESULTS.md if a subfolder is not practical
  logcat excerpts and android-log export paths
```

If the botshare hub can only write text, say so in `RESULTS.md`, still store `RESULTS.md` and the log excerpts there, and keep the screenshots on the tester machine with a path noted in the result.

android-log exports stay under the usual android-log export path; name that path in `RESULTS.md`.

## Cleanup

Leave mfx-nfstest and the local test folder as they were: delete folders and files you created, reset folder view/sort if you changed them, close extra tabs, and turn airplane mode off. Do not touch unrelated data on the share.
