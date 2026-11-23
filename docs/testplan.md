# MF Explorer test plan

Fixed checklist for release candidates of the [WackEarl/MF-Explorer](https://github.com/WackEarl/MF-Explorer) fork. New or changed behavior is tested in addition to this smoke suite, not instead of it.

The run must not crash: no process crash, no forced close, no stuck ANR. Record this as **S17** in the result.

## How to use

For each release candidate:

1. Gary publishes the RC and posts a short test request with a link to this file, plus **only** the new or changed points for that version (`R1`, `R2`, …). The fixed smoke suite below is always in scope and is not repeated in the request unless a step itself changed.
2. Cody runs the fixed smoke suite and the new points on the environment below.
3. Cody stores the result as `RESULTS.md` on botshare under `tests/mf-explorer/<VERSION>/` (see [Artifacts](#artifacts)), using the [template](#result-template): Pass, Fail, N/A or Not checked per point, version, device or AVD, and date. Cody does **not** comment on GitHub.
4. Cody posts the botshare path in the Dev group. Gary copies the text of `RESULTS.md` into a comment on the GitHub release issue.

`<VERSION>` is the exact `versionName`, for example `2.1.8-rc1` or `2.1.8`.

## Environment

- **Device:** Unraid emulator AVD `nexus_4_14.0` (API 34).
- **Start and stop:** Cody starts and stops the emulator himself via test-container, not via Homey.
  - Before starting, check `locks/emulator.md` and `locks/agent-device.md` on botshare and mark them taken (see #60).
  - After start, wait about 2 minutes. Do not restart `agent-device-mcp`.
  - Export logcat **before** stopping the emulator.
  - After stopping, set both locks back to free.
- **Share:** NFS share **mfx-nfstest** only. Do not mount or open any other share.
- **Install:** agent-device with `appPath=/apk/<file>.apk`. Verify the sha256 of the APK against the GitHub release before installing.
- **Update, not clean:** unless the test request says otherwise, install over the previous release or RC. After the update, check that tabs, NFS connections and folder settings are still there (S2).
- **Safety:** do not use or mention crypt paths. Do not record secrets, passwords, keystores or tokens in results. In comments and artifacts, name the share as `mfx-nfstest`; do not add host IPs or extra paths. Do not touch large files (including `big-2g.bin`). Do not run the same case repeatedly. Use only small test files you created yourself, and clean them up afterwards.

A fixed, script-resettable dataset on mfx-nfstest is **out of scope** for this plan (tracked separately). Use the current contents of the share, and clean up what you create (see [Cleanup](#cleanup)).

GitHub Actions emulator jobs, Firebase Test Lab, and features inherited from upstream that this fork does not change (archives, FTP/SFTP/SMB/WebDAV, root, Shizuku) are also out of scope unless a release touches them. Cover those only as new `R` points.

## Fixed smoke suite

| ID | What to check | Pass when |
|----|---------------|-----------|
| S1 | App start | The app opens to a usable file list, with no crash or ANR. About shows the expected `versionName` and `versionCode`. |
| S2 | Update keeps state | After an update-install, previous tabs, the NFS connection and folder view/sort settings are still there. |
| S3 | Local listing | A local folder (for example the usual on-device test folder) lists without error. |
| S4 | NFS listing | The mfx-nfstest root lists (folders and files appear) within a few seconds. A loading indicator is acceptable. |
| S6 | Tabs and plus button | Existing tabs switch correctly. The last tab stays visible beside "+" (not covered). A new tab opened with "+" is scrolled into view. Until 2.1.9, a white box behind "+" is a known cosmetic issue: do not fail the point; mention it under notes if seen. |
| S7 | Search, query kept after back | Start a search and type a query that returns hits. Hide the keyboard with back: the query and results stay, and the toolbar subtitle still shows the term. The next back, or clearing the query, leaves search and restores the unfiltered list. |
| S8 | New folder scrolls into view | Create a folder with "New folder". If "Folders first" would keep it on screen anyway, turn that off first so the new name would otherwise land off-screen. The new folder is scrolled into view. |
| S9 | Rename | Rename a small self-created test file or folder from the row menu (on mfx-nfstest or locally). The new name is shown, with no crash. |
| S10 | Copy local→NFS and NFS→NFS | Copy a small set of self-created test files both ways. Both copies succeed. Note the duration and approximate size in the result, for later speed comparison. Clean up afterwards. |
| S11 | Move NFS→local | Cut a small self-created test file on mfx-nfstest and paste it into a local folder. The file arrives and the source is gone. A log line about `AtomicMoveNotSupportedException` during the copy-and-delete fallback is a known cosmetic issue: do not fail the point; mention it under notes if seen. |
| S12 (online) | Delete while online | Delete a small self-created test file while the network is up; it disappears. The offline half of S12 is part of the [offline flow](#offline-flow-s16-s12-s5-s18). |
| S13 | Thumbnails while scrolling | In a folder with images and, if present, videos on mfx-nfstest, scroll the list or grid. Thumbnails for the images and, if present, the videos appear, and scrolling stays usable (no long freeze). |
| S14 | Sort and view per folder | Set folder A to a non-default view/sort (for example grid) and leave folder B on the default. The settings survive folder switches and an app restart. "Reset folder view and sort" restores the default for that folder only. "Use as default for all folders" applies to other folders. Reset afterwards so the share is left as you found it. Exclusion of these settings from Android backup is a code/privacy check, not a device check; record it as N/A or Not checked unless the test request asks for it. |
| S15 | Rotation and back | Rotate the device in a folder list and in search. A back press right after rotation is not lost. The current folder, and an active search if any, is preserved. If agent-device cannot rotate the AVD, record S15 as N/A or Not checked and say why. |
| S16, S12 (offline), S5, S18 | Offline flow | Follow the [offline flow](#offline-flow-s16-s12-s5-s18) in **one** pass (no repeat runs). Pass when every step in that flow passes. Record each ID in the result. |

## Offline flow (S16, S12, S5, S18)

Run this sequence once, after the online listing and the online delete (S12) have already been checked. Do not repeat it.

1. Open an NFS folder on mfx-nfstest.
2. Turn airplane mode on.
3. **S16 — Network loss message.** A readable error is shown (no raw RPC or Java class names). When there is no network, the failure should be fast (around a second, not tens of seconds).
4. **S12 — Delete, offline.** Delete a small self-created test file on mfx-nfstest. An error is shown (toast or dialog, for example that the NFS server is unreachable), the file stays, and there is no crash. Until 2.1.9, a toast instead of a snackbar or a dialog with Retry is a known cosmetic issue: do not fail the point; mention it under notes if seen.
5. Turn airplane mode off.
6. **S5 — Breadcrumb reload.** Tap the **current** (selected) breadcrumb segment. The folder must reload (Loading, then the list within about 4 s). Navigating with breadcrumbs should also work during the rest of the run.
7. **S18 — Logcat.** Export logcat via android-log **before** stopping the emulator. For the failed folder listing, the log shows one stack trace per error, not a repeat when the same cached failure is shown again. Keep the export path in the result.

## Release-specific points

Gary lists only new or changed checks as `R1`, `R2`, … in the RC test request, with a link to this file. Cody runs them in addition to the smoke suite and records them in the same table.

## Result template

Store this as `RESULTS.md` on botshare under `tests/mf-explorer/<VERSION>/`. Post that path in Dev; Gary copies the text into the GitHub release issue.

```markdown
## Test result: <versionName> (<versionCode>)

- Date: YYYY-MM-DD (Europe/Berlin)
- Tester: Cody
- Device: AVD nexus_4_14.0 (API 34)
- Install: update from <previous version> via `appPath=/apk/<file>.apk` (or clean, if requested)
- sha256: matches the GitHub release / mismatch
- Artifacts: botshare `tests/mf-explorer/<VERSION>/`
- Times in Europe/Berlin, logcat is UTC

Statuses: Pass / Fail / N/A / Not checked

| ID | Point | Result | Notes |
|----|-------|--------|-------|
| S1 | App start | Pass / Fail / N/A / Not checked | |
| S2 | Update keeps state | Pass / Fail / N/A / Not checked | |
| S3 | Local listing | Pass / Fail / N/A / Not checked | |
| S4 | NFS listing | Pass / Fail / N/A / Not checked | |
| S5 | Breadcrumb, including failed-folder reload | Pass / Fail / N/A / Not checked | offline flow |
| S6 | Tabs and plus button | Pass / Fail / N/A / Not checked | |
| S7 | Search, query kept after back | Pass / Fail / N/A / Not checked | |
| S8 | New folder scrolls into view | Pass / Fail / N/A / Not checked | |
| S9 | Rename | Pass / Fail / N/A / Not checked | |
| S10 | Copy local→NFS and NFS→NFS | Pass / Fail / N/A / Not checked | duration and size: |
| S11 | Move NFS→local | Pass / Fail / N/A / Not checked | |
| S12 | Delete, online and offline | Pass / Fail / N/A / Not checked | offline flow |
| S13 | Thumbnails while scrolling | Pass / Fail / N/A / Not checked | |
| S14 | Sort and view per folder | Pass / Fail / N/A / Not checked | backup exclusion: |
| S15 | Rotation and back | Pass / Fail / N/A / Not checked | |
| S16 | Network loss message | Pass / Fail / N/A / Not checked | offline flow |
| S17 | No crash | Pass / Fail / N/A / Not checked | |
| S18 | Logcat: no duplicate stack traces | Pass / Fail / N/A / Not checked | export: |
| R1 | <new point from the test request> | Pass / Fail / N/A / Not checked | |

### Findings

- Severity (critical / annoying / cosmetic): where, steps to reproduce, expected vs actual, screenshot or log, suggested fix.

### Cleanup

What was deleted or reset. Airplane mode off. Locks set to free. Emulator and containers left as agreed.
```

## Artifacts

On botshare:

```
tests/mf-explorer/<VERSION>/
  RESULTS.md          # the result Cody posts the path to in Dev
  screenshots/        # or PNG files next to RESULTS.md if a subfolder is not practical
  logcat excerpts and android-log export paths
```

If the botshare hub can only write text, say so in `RESULTS.md`, still store `RESULTS.md` and the log excerpts there, and keep the screenshots on the tester machine with a path noted in the result.

android-log exports stay under the usual android-log export path; name that path in `RESULTS.md`. Export logcat before stopping the emulator.

## Cleanup

Leave mfx-nfstest and the local test folder as they were: delete folders and files you created, reset folder view/sort if you changed them, close extra tabs, and turn airplane mode off. Do not touch unrelated data on the share (including large files such as `big-2g.bin`). After stopping the emulator, set `locks/emulator.md` and `locks/agent-device.md` back to free.
