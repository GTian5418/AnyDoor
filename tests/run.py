"""Host regression tests against production classes. No device integration is implied.
Requires JDK 17, Python 3, Node, Android platform jar, and Bash for RootShell tests.
"""
from pathlib import Path
import os,subprocess,shutil,urllib.request,hashlib
ROOT=Path(__file__).resolve().parent.parent
SRC=ROOT/'app/src/main/java/io/github/zhaoyuxiangyyds_lab/anydoor'
OUT=ROOT/'build/tests'; OUT.mkdir(parents=True,exist_ok=True)
SDK=Path(os.environ.get('ANDROID_SDK_ROOT','D:/Android/Sdk'))
ANDROID=Path(os.environ.get('ANDROID_JAR',str(SDK/'platforms/android-34/android.jar')))
JSON=OUT/'json-20240303.jar'
HASH='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not JSON.exists():
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30) as r: JSON.write_bytes(r.read())
assert hashlib.sha256(JSON.read_bytes()).hexdigest()==HASH,'JSON dependency checksum mismatch'
KXML=OUT/'kxml2-2.3.0.jar'
if not KXML.exists():
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar',timeout=30) as r: KXML.write_bytes(r.read())
assert hashlib.sha256(KXML.read_bytes()).hexdigest()=='f264dd9f79a1fde10ce5ecc53221eff24be4c9331c830b7d52f2f08a7b633de2'
assert ANDROID.exists(), 'Set ANDROID_JAR or ANDROID_SDK_ROOT'
CLASSES=OUT/'classes';CLASSES.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[JSON,KXML,ANDROID]))
files=[SRC/n for n in ['Keys.java','GeoMath.java','Geocoder.java','ConfigSnapshot.java','ProviderController.java','RootShell.java','MirrorCommand.java','LegacyPrefsMigration.java','xposed/SpoofState.java']]
files+=list((ROOT/'tests/stubs').rglob('*.java'))
files+=list((ROOT/'tests/io').rglob('*.java'))
subprocess.run(['javac','--release','8','-encoding','UTF-8','-cp',cp,'-d',str(CLASSES)]+list(map(str,files)),check=True)
runtime=os.pathsep.join(map(str,[CLASSES,JSON,KXML,ANDROID]))
base=['java',f'-Dtest.mirror={OUT / "mirror.json"}','-cp',runtime]
for cls in ['CoreTest','xposed.StateTest','MigrationTest','RouteTest']:
    subprocess.run(base+['io.github.zhaoyuxiangyyds_lab.anydoor.'+cls],check=True)
bash=os.environ.get('TEST_BASH') or (r'C:/Program Files/Git/bin/bash.exe' if os.name=='nt' else shutil.which('bash'))
subprocess.run(base+['io.github.zhaoyuxiangyyds_lab.anydoor.ShellTest',bash],check=True)
subprocess.run(base+['io.github.zhaoyuxiangyyds_lab.anydoor.MirrorTest',bash,str(OUT)],check=True)
# RealLocationRequest is compiled on its own, against dedicated minimal android.location/android.os
# stubs in tests/location, so those stubs never reach the production/regression classpath above.
LOC=OUT/'location-classes';LOC.mkdir(exist_ok=True)
loc_src=[SRC/'RealLocationRequest.java']+list((ROOT/'tests/location').rglob('*.java'))
subprocess.run(['javac','--release','8','-encoding','UTF-8','-cp',str(ANDROID),'-d',str(LOC)]+list(map(str,loc_src)),check=True)
loc_runtime=os.pathsep.join(map(str,[LOC,ANDROID]))
subprocess.run(['java','-cp',loc_runtime,'io.github.zhaoyuxiangyyds_lab.anydoor.LocationRequestTest'],check=True)
node=os.environ.get('TEST_NODE') or shutil.which('node') or 'node'
subprocess.run([node,str(ROOT/'tests/diagnostics.cjs')],check=True)
bridge=ROOT/'tests/location-bridge.cjs'
if bridge.exists(): subprocess.run([node,str(bridge)],check=True)
