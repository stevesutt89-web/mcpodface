# On-device testing checklist

Tick items after checking on a real phone (S25 Ultra / S23+).

## Phase 0
- [ ] App installs (`./gradlew :app:installDebug`) and launches with the McPodface splash.
- [ ] Five tabs show: Latest · Library · Downloads · Up Next · Search.
- [ ] Switch to e.g. Downloads, swipe the app away, relaunch: it reopens on Downloads with no flash of Latest.
- [ ] Gear icon on Latest and Library opens Settings; back returns.
- [ ] Settings → Theme Light / Dark / System switches immediately, and status/nav bar icons stay readable.
- [ ] Pure black on: dark background is true black. Off: dark grey.
- [ ] Content draws edge to edge (behind status and gesture bars) without being covered.
- [ ] Predictive back gesture shows the back preview from Settings.

## Android Auto

Run these in the car, or on the Desktop Head Unit where noted. Tick items after checking them.

- [ ] Sideloaded build only: in the Android Auto developer settings on the phone, turn on Unknown sources.
- [ ] Desktop Head Unit only: start it as described in `docs/ANDROID-AUTO.md` under "How to test". The car screen opens with the phone connected.
- [ ] Connect the phone to the car. McPodface appears in the car's media apps.
- [ ] The car's home screen shows a resume card with the last episode. Tap it: that episode plays.
- [ ] Press play/pause on the car screen. Playback pauses, then plays again when pressed.
- [ ] Press play/pause on the steering-wheel button. Playback pauses, then plays again. (This was the original bug.)
- [ ] Press skip back on the car screen. Playback jumps back by the same interval as the phone's skip back button.
- [ ] Press skip forward on the car screen. Playback jumps forward by the same interval as the phone's skip forward button.
- [ ] Drag the seek bar. Playback moves to that point.
- [ ] Up Next lists the queue in order, with the current episode first.
- [ ] Latest shows episodes from the last 30 days, grouped under Today, Yesterday, This week and Earlier.
- [ ] Latest hides played episodes.
- [ ] Podcasts shows a grid of podcast artwork.
- [ ] Tap a podcast in Podcasts. Its episodes are listed, with in-progress episodes first.
- [ ] Library shows the folders Downloads, In progress, Starred, Playlists and History.
- [ ] Downloads, In progress and Starred each show only the episodes that match their name.
- [ ] Playlists lists every playlist, including smart ones. Tap a playlist: its episodes are listed.
- [ ] History lists recently played episodes.
- [ ] A played episode shows the played badge.
- [ ] A part-played episode shows how far you have listened.
- [ ] A downloaded episode shows the downloaded badge.
- [ ] An episode you have started shows "Podcast · N min left" as its subtitle. An unplayed episode shows its length, such as "· 1 h 05 min".
- [ ] Tap the Up Next action on an episode row to add it, then tap again to remove it. The list updates within a second or two, and the phone's Up Next shows the same change.
- [ ] Tap Download on an episode row. When it finishes, the row shows the downloaded badge and the action changes to Remove download. Tap Remove download: the file is deleted and the badge goes.
- [ ] Tap Star on an episode row. The phone shows the episode as starred. Tap again to unstar it.
- [ ] Tap Mark played on an episode row. Its badge updates within a second or two, and the phone shows the same change.
- [ ] In the now-playing overflow, Speed cycles 1.0x → 1.2x → 1.5x → 1.8x → 2.0x on each tap.
- [ ] Set Speed to 1.5x, then play another episode of the same podcast. It plays at 1.5x.
- [ ] In the now-playing overflow, Star stars the episode that is playing.
- [ ] In the now-playing overflow, Mark played & next marks the episode played and starts the first Up Next item.
- [ ] In the now-playing overflow, Sleep cycles Off → 15 min → 30 min → End of episode. With End of episode set, playback stops at the end of the episode.
- [ ] Say "Hey Google, play McPodface". The last episode resumes.
- [ ] Say "Hey Google, play `<podcast name>` on McPodface". That podcast's in-progress episode plays, or its newest unplayed one if none is in progress.
- [ ] Say "Hey Google, play `<topic>` on McPodface". An episode whose text matches the topic plays.
- [ ] With the car connected, add an episode to Up Next on the phone. The car's Up Next shows it without reconnecting.
- [ ] With no signal, open a streamed episode. The car shows "No connection. Downloaded episodes still play."
- [ ] With no signal, play a downloaded episode. It plays.
- [ ] Turn the car off in the middle of an episode, then turn it on again. The episode is still at the same position.
- [ ] After reconnecting, the resume card shows that episode. Tap it: playback continues from the same position.
- [ ] With the car not connected, the phone notification shows only skip back, play/pause and skip forward.
- [ ] The phone's lock screen controls play, pause and skip.
- [ ] Galaxy Buds play/pause and skip controls still work on the phone.
