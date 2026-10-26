#!/usr/bin/env python3
"""Sign every release with the original private ForFont-signing-backup key."""
import argparse,hashlib,json,os,pathlib,subprocess,tempfile,zipfile
p=argparse.ArgumentParser();p.add_argument('unsigned_apk');p.add_argument('output_apk');p.add_argument('--backup',required=True);p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')));a=p.parse_args()
expected='80400d4854a78108b4be90802ea183d23f659deebe1e20a4340cf7b7e927478a'
builds=pathlib.Path(a.sdk,'build-tools');versions=sorted(builds.glob('*'),key=lambda x:tuple(int(n)if n.isdigit()else 0 for n in x.name.split('.')));assert versions,'Install Android build-tools first';tools=versions[-1]
with tempfile.TemporaryDirectory(prefix='forfont-sign-')as temp:
 root=pathlib.Path(temp)
 with zipfile.ZipFile(a.backup)as z:
  for name in ['signing.json','forfont-release.p12','certificate.der']:(root/name).write_bytes(z.read(name))
 assert hashlib.sha256((root/'certificate.der').read_bytes()).hexdigest()==expected,'Wrong signing backup: do not generate another key'
 meta=json.loads((root/'signing.json').read_text());env=os.environ.copy();env['FORFONT_SIGN_PASSWORD']=meta['password'];aligned=root/'aligned.apk'
 subprocess.run([str(tools/'zipalign'),'-f','-p','4',a.unsigned_apk,str(aligned)],check=True)
 subprocess.run([str(tools/'apksigner'),'sign','--ks',str(root/'forfont-release.p12'),'--ks-type','PKCS12','--ks-key-alias',meta['alias'],'--ks-pass','env:FORFONT_SIGN_PASSWORD','--key-pass','env:FORFONT_SIGN_PASSWORD','--out',a.output_apk,str(aligned)],env=env,check=True)
 result=subprocess.run([str(tools/'apksigner'),'verify','--verbose','--print-certs',a.output_apk],capture_output=True,text=True,check=True)
 assert expected in result.stdout.lower().replace(':',''),'APK certificate did not match the fixed signing identity'
 print(result.stdout)
