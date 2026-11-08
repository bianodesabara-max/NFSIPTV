import re, subprocess, sys, time
alvo = sys.argv[1]
for tentativa in range(5):
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n); d = re.search(r'content-desc="([^"]*)"', n)
        textos = (t.group(1) if t else "") + "|" + (d.group(1) if d else "")
        if alvo.lower() in textos.lower():
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            x = (int(b.group(1)) + int(b.group(3))) // 2; y = (int(b.group(2)) + int(b.group(4))) // 2
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
            print(f"tocou '{alvo}' em {x},{y}")
            sys.exit(0)
    time.sleep(2)
print(f"NAO ACHOU '{alvo}'")
