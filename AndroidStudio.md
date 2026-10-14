## Android Studio
# Use/Install apk on cell phone

1. Use Cell Phone, Pixel 6a, to debug devices
   - [Setting] -> [About phone] -> Press [Build mumber]  7 times, to change to devoper mode.
   - Enable MTP, or dat transfer, <br>
     - Press [Setting] -> [System] -> [{} eveloper options] -> [USB Debugging]
  - While conected with PC, in popout, ("Charging this device via USB"), and select File Transfer or MTP.
2. Generated a "signed" apk to install on Pixel cell phone
   - enable top menu
   - [Build] -> [Generate App Bundels or APKs] -> both [Generate APKS] and [Generate Bundles]
   - [Generate Signed App Bundels or APKs], only have this to install, which is [$ProjectHome/app/build/outputs/apk/debug/app-debug.apk]
3. Open the [Console] from left-buttom menu to install apk to cell phone<br>
``` > adb install -r app/build/outputs/apk/debug/app-debug.apk```
   
