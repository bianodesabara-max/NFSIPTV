#!/bin/bash
set -x
mkdir -p capturas
foto() { adb exec-out screencap -p > "capturas/$1.png"; }
toque() { python3 .github/teste/toque.py "$1" | tee -a capturas/passos.txt; }
adb install -r NFS-IPTV.apk
adb logcat -c
adb shell am start -n com.nfsiptv.app/nfs.iptv.MainActivity
sleep 10; foto 1-inicio
toque "Adicionar lista"; sleep 3
toque "Link M3U"; sleep 2
toque "Link da lista"; sleep 1
adb shell input text "http://10.0.2.2:8000/.github/teste/teste.m3u"
sleep 1; adb shell input keyevent 111; sleep 1; foto 2-dialogo
toque "Salvar"; sleep 10; foto 3-categorias
toque "Testes HLS"; sleep 4; foto 4-canais
toque "Canal Teste Mux"; sleep 20; foto 5-player
adb shell input keyevent KEYCODE_DPAD_UP; sleep 15; foto 6-proximo-canal
adb shell input tap 540 1200; sleep 2; foto 7-controles
adb shell input keyevent KEYCODE_BACK; sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 3; foto 8-voltou
adb shell am force-stop com.nfsiptv.app; sleep 2
adb shell am start -n com.nfsiptv.app/nfs.iptv.MainActivity; sleep 8; foto 9-reabriu
adb logcat -d > capturas/logcat-completo.txt
grep -E "FATAL|AndroidRuntime|nfs.iptv|ExoPlayer|MediaCodec.*rror" capturas/logcat-completo.txt | tail -200 > capturas/logcat.txt
true
