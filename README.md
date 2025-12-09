# Headunit Revived

<p align="center">
    <img src="https://github.com/user-attachments/assets/20c3d622-89dc-4c20-8eae-b43074f3c144"
    alt="Headunit Logo"
    height="200">
</p>

This project is a revived version of the original headunit project by the great Michael Reid. The original project can be found here:
https://github.com/mikereidis/headunit

## Screenshots
<img width="1280" height="800" alt="image" src="https://github.com/user-attachments/assets/22abbc13-75d5-436f-b0ae-2e92b7648d50" />
<img width="1280" height="800" alt="image" src="https://github.com/user-attachments/assets/f81149b3-a844-4657-87d2-a2867a5eb030" />
<img width="1280" height="800" alt="image" src="https://github.com/user-attachments/assets/140bbfdb-5b4f-4d49-a419-85aa91b48371" />


## Changelog
### v1.1.0 - New Design
- Changed the basic design to a modern look and bigger buttons
- Hopefully fixed audio-stutters with audio thread and some logs
- removed some deprecations

### v1.0.0 - Initial Revived Release
- Updated dependencies to latest versions.
- Improved compatibility with newer Android versions.
- Added Multitouch-Support
- Some sort of wireless support with Headunit-Server on Phone

## Contributing

Creating release apk needs a keystore file. You can create your own keystore file using the following command in root folder:
`keytool -genkey -v -keystore headunit-release-key.jks -alias headunit-revived -keyalg RSA -keysize 2048 -validity 10000`  

After that you need to set the env variables depending on your OS:
MAC:
open ~/.zshrc or ~/.bashrc

`sudo nano ~/.zshrc or sudo nano ~/.bashrc`   
`export HEADUNIT_KEYSTORE_PASSWORD="YOUR_KEYSTORE_PASSWORD"  
export HEADUNIT_KEY_PASSWORD="YOUR_KEY_PASSWORD"`  

## Original Headunit
Headunit for Android Auto (tm)

A new car or a $600+ headunit is NOT required to enjoy the integration and distraction reduced environment of Android Auto.

This headunit app can turn a Android 4.1+ tablet supporting USB Host mode into a basic Android Auto compatible headunit.

Android, Google Play, Google Maps, Google Play Music and Android Auto are trademarks of Google Inc.
