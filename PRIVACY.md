# Privacy Policy

MF Explorer is a modified fork of [Material Files](https://github.com/zhanghai/MaterialFiles) by Hai Zhang.

This policy describes what this fork does with data, as implemented in the current source. It does not speak for the original Material Files app or for any other distribution of that project.

## Data stored on the device

Settings, bookmarks, folder tabs, folder view and the servers you add (including passwords, SFTP private keys and their passphrases, and WebDAV access tokens) are stored locally on the device.

The app does not take part in Android Auto Backup, cloud backup or device-to-device transfer (`android:allowBackup` is false, and the backup and data-extraction rules exclude all app data). To copy settings to another device, use the in-app export. The export file stores server passwords, the FTP server password, SFTP private keys and their passphrases, and WebDAV access tokens in plain text. Anyone who has that file can read them.

The app may also keep ordinary local state such as caches and logs on the device. Log output can appear in the system log (logcat).

## Network

The app has Internet permission so that you can reach file servers and, if you start it, run the built-in FTP server.

Connections to FTP, FTPS, SFTP, SMB, WebDAV and NFS are made only to servers that you add and choose to use. Credentials for those servers stay on the device unless you export them as described above. The built-in FTP server accepts connections only when you start it.

The app does not include a setting or mechanism that phones home to a project update or telemetry server of this fork.

## Analytics and crash reporting

This fork does not include analytics or crash-reporting libraries, and it does not send usage or crash data to a third-party service.

## Contact

Questions about this policy can be opened as a GitHub issue at https://github.com/WackEarl/MF-Explorer/issues.
