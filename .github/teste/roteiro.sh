#!/bin/bash
set -x
mkdir -p capturas
foto() { adb exec-out screencap -p > "capturas/$1.png"; }
toque() { python3 .github/teste/toque.py "$1" | tee -a capturas/passos.txt; }
adb install -r NFS-IPTV.apk
adb shell am start -n com.nfsiptv.app/nfs.iptv.MainActivity
sleep 10
toque "Adicionar lista"; sleep 3
toque "Link M3U"; sleep 2
toque "Link da lista"; sleep 1
adb shell input text "http://10.0.2.2:8000/.github/teste/teste.m3u"
sleep 1; adb shell input keyevent 111; sleep 1
toque "Salvar"; sleep 10
toque "Testes MP4"; sleep 4
toque "Video MP4 Teste"; sleep 20; foto 1-preencher
adb shell input tap 1140 540; sleep 1
toque "Preencher"; sleep 3; foto 2-esticar
adb shell input tap 1140 540; sleep 1
toque "Esticar"; sleep 3; foto 3-ajustar
adb shell input tap 1140 540; sleep 1; foto 4-controles
adb shell pidof com.nfsiptv.app >/dev/null && echo "APP ABERTO" >> capturas/passos.txt
true
