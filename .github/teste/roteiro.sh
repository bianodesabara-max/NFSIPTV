#!/bin/bash
set -x
mkdir -p capturas
foto() { adb exec-out screencap -p > "capturas/$1.png"; }
toque() { python3 .github/teste/toque.py "$1" | tee -a capturas/passos.txt; }
vivo() { adb shell pidof com.nfsiptv.app >/dev/null && echo "$1: APP ABERTO" || echo "$1: APP FECHOU"; } 
adb install -r NFS-IPTV.apk
adb logcat -c
adb shell am start -n com.nfsiptv.app/nfs.iptv.MainActivity
sleep 10
toque "Adicionar lista"; sleep 3
toque "Servidor"; sleep 1
for parte in "h" "t" "t" "p" ":" "/" "/" "teste.exemplo.com:8080"; do adb shell input text "$parte"; sleep 0.5; done
sleep 1; vivo "depois de digitar http://" | tee -a capturas/passos.txt; foto 1-servidor
adb shell input text "/get.php?username=joao\&password=senha123\&type=m3u_plus"
sleep 2; vivo "depois de colar link completo" | tee -a capturas/passos.txt
adb shell input keyevent 111; sleep 1; foto 2-preenchido
toque "Salvar"; sleep 25; vivo "depois de salvar" | tee -a capturas/passos.txt; foto 3-erro-servidor
adb logcat -d > capturas/logcat-completo.txt
grep -E "FATAL|AndroidRuntime: (FATAL|java|Caused|	at nfs)" -A3 capturas/logcat-completo.txt | head -60 > capturas/crash.txt
true
