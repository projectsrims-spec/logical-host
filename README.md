# Logical Host - Android app v2.14

Native floating bubble removed. The bubble is now the web page's own bubble,
skinned with the app logo (bridge.js).

- The bubble shows ONLY while the bot is running (the web page decides this).
- Tap the bubble: the web bot console opens (same as before).
- Tap the "Bot is running" notification or a signal alert: the app opens
  straight onto the web bot console.
- No "display over other apps" permission is requested anymore.

Files that matter:
- app/src/main/java/com/logicalhost/app/KeepAliveService.java  foreground service, Stop action
- app/src/main/java/com/logicalhost/app/WebHost.java           one shared WebView, openConsole()
- app/src/main/java/com/logicalhost/app/MainActivity.java      the screen, opens console on tap
- app/src/main/java/com/logicalhost/app/NotifyBridge.java      notifications + botState() from the page
- app/src/main/assets/bridge.js                                skins .float-bubble with the logo, signal alerts

Change the page the app opens: HOME_URL in WebHost.java.
Logo: the same vector as res/drawable/ic_fg.xml, duplicated as SVG in bridge.js (LOGO_SVG).

App icon (v2.8): the robot logo with its background removed, in res/drawable-nodpi/ic_logo.png,
used as the adaptive launcher icon foreground (see mipmap-anydpi-v26/ic_launcher.xml).

v2.9: notification icon is the robot silhouette (res/drawable-nodpi/ic_notif.png, white with
transparent background, as Android requires for status-bar icons). The web bubble logo is the
robot embedded in bridge.js (LOGO_URL), replacing the old SVG L/H logo.

v2.10: cleanup only. No native overlay bubble remains; the page's .float-bubble is the only bubble.
Removed the unused ic_fg.xml vector and corrected stale comments.

v2.11: the web bubble is no longer restyled with CSS. bridge.js only swaps the picture inside the
page's own bubble <img> to the robot, so the bubble draws the way the web app designs it.

v2.12: when the app returns to the front, WebHost.ensureHome() checks the page. If it is blank or
not on the web app's Home route (/android), it loads Home again. A drawn Home page is left alone.

v2.13: status notifications. "Bot is ON" and "Bot is OFF" are sent when the page's bubble appears or
disappears, and "Executing trade" when the bot places a trade. Each change must hold for 4 seconds
(1.5 s for execution) so page reloads do not cause false alerts. Shown only while the app is in the
background. Signal alerts are unchanged. The app keeps running in the background through the
foreground service and wake lock, as before.

v2.14: native floating bubble over other apps. Shown only while the bot is on and the app is not on
screen (needs "Display over other apps" permission, which the app asks for). Tapping the bubble opens
a small bot console card over the current app: bot status, self-host EA status, recent activity, and
an OPEN APP button. The bubble does not bring the app to the front. Bubble colours: green on, orange
while executing, grey when the self-host EA is offline. Activity lines come from the same signal and
status events as the notifications.
