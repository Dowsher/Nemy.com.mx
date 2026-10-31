"""Provisiona un vínculo local por ADB. No guarda ni imprime la clave."""
import argparse, os, secrets, shutil, subprocess
p=argparse.ArgumentParser()
p.add_argument("--telefono",required=True);p.add_argument("--reloj",required=True)
p.add_argument("--renovar",action="store_true",help="Reemplazar deliberadamente un vínculo previo en ambos dispositivos")
a=p.parse_args()
adb=shutil.which("adb") or os.path.join(os.environ["LOCALAPPDATA"],"Android","Sdk","platform-tools","adb.exe")
serials=[a.telefono,a.reloj]
if len(set(serials))!=2:raise SystemExit("Selecciona dos dispositivos distintos.")
for serial in serials:
    subprocess.run([adb,"-s",serial,"get-state"],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
    existing=subprocess.run([adb,"-s",serial,"shell","run-as","org.pipalab.wear","test","-f","shared_prefs/local-link.xml"],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
    if existing.returncode==0 and not a.renovar:raise SystemExit("Hay un vínculo previo. Actualizar una APK no requiere renovarlo.")
key=secrets.token_bytes(32)
try:
    for serial in serials:
        subprocess.run([adb,"-s",serial,"shell","run-as","org.pipalab.wear","mkdir","-p","files"],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
        subprocess.run([adb,"-s",serial,"shell","-T","run-as","org.pipalab.wear","dd","of=files/pairing.key"],input=key,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
        print(serial+": clave provisionada; activar el enlace en la app.")
finally:
    del key
