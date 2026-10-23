# AndroidManifest.xml additions for v1.2.0

Add inside <manifest> (next to the existing permissions):

```xml
<uses-permission android:name="android.permission.CAMERA" />                       <!-- Lens & IR finder -->
<uses-permission android:name="android.permission.INTERNET" />                     <!-- ONLY for the optional camera-maker database download -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />         <!-- find the Wi-Fi network for the LAN scan -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />  <!-- ONVIF discovery -->
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.flash" android:required="false" />
```

No FileProvider is needed: the PDF is saved with the system "Create document" picker, like the CSV export.

Before release
- Pro billing: see the header of PlayBilling.kt.txt (dependency, product id, wiring, Pro.ENFORCE = true).
- Play Console: declare camera use and the INTERNET use (database download) in the Data safety form; say that scanning is processed on the device only.
- Store text: say the app gives hints and cannot guarantee finding hidden cameras.
- LAN scan: keep the consent dialog; it must stay limited to the network the user is connected to.
