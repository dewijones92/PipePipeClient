## README

The client of [PipePipe](https://codeberg.org/NullPointerException/PipePipe).

This fork is **DewiPipe**: PipePipe with YouTube playback and downloads served by a
bundled yt-dlp stack (see `dewijones92/youtubedl-android`), SponsorBlock-on-download,
and per-commit versioning. Install the APKs from the
[releases page](https://github.com/dewijones92/PipePipeClient/releases) — milestone
`ytdlp-poc-v*` tags are the stable drops; Obtainium can track this repo for updates.

Debug builds are signed with the committed `keystore/dewipipe-debug.keystore`
(deliberately public, debug-only identity) so local and CI builds share one
certificate and updates always install over previous releases.
