#!/bin/sh
# Best-effort check of the gallery album picker: create a few images, grant read
# access, tap "Galeri Albümü Ekle" and print what the album dialog lists.
set -x
for i in 1 2 3; do
  adb shell screencap -p /sdcard/Pictures/kolaj_test_$i.png
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/kolaj_test_$i.png > /dev/null
done
adb shell content query --uri content://media/external/images/media --projection _id:bucket_display_name | head -5
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api" -ge 33 ]; then
  adb shell pm grant com.superzeka.kolaj android.permission.READ_MEDIA_IMAGES
else
  adb shell pm grant com.superzeka.kolaj android.permission.READ_EXTERNAL_STORAGE
fi
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
bounds=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -E 'text="(GALER[^"]*ALB|Galeri Alb)' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+')
set -- $bounds
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
sleep 4
adb shell uiautomator dump /sdcard/ui2.xml > /dev/null
adb shell cat /sdcard/ui2.xml | tr '>' '\n' | grep -oE 'text="[^"]+"' | head -20
adb shell pidof com.superzeka.kolaj || { echo "APP DIED"; adb logcat -d -s AndroidRuntime:E | tail -40; exit 1; }
