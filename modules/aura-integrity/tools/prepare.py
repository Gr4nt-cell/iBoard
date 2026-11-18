#!/usr/bin/env python3
import argparse, hashlib, json, shutil, subprocess, urllib.request
from pathlib import Path

UPSTREAM='622eb9c50f637a6f56597cb4ce0312e165106c9c'
LSPLT='3e29437f037cb7d2b9fbb459dcf162f6b8d1d926'
BASE=Path(__file__).resolve().parents[1]

def prepare(root):
    actual=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    assert actual==UPSTREAM,(actual,UPSTREAM)
    actual_sub=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root/'app/src/main/cpp/external/LSPlt',text=True).strip()
    assert actual_sub==LSPLT,(actual_sub,LSPLT)
    changes=[]
    # Remove every upstream module file, including its example AOSP software private key.
    shutil.rmtree(root/'module')
    shutil.copytree(BASE/'module',root/'module')
    for path in (root/'app/src').rglob('*'):
        if path.is_file() and path.suffix in ['.kt','.java','.cpp','.hpp','.h']:
            text=path.read_text()
            if '/data/adb/tricky_store' in text:
                path.write_text(text.replace('/data/adb/tricky_store','/data/adb/aura_integrity'))
                changes.append(str(path.relative_to(root)))
    assert changes,'Expected upstream configuration path was not found'
    helper=root/'app/src/main/java/org/matrix/TEESimulator/pki/CertificateHelper.kt'
    text=helper.read_text();start=text.index('    fun parsePemKeyPair(');brace=text.index('{',start)
    depth=1;end=brace+1
    while depth:
        if text[end]=='{':depth+=1
        if text[end]=='}':depth-=1
        end+=1
    text=text[:start]+"""    fun parsePemKeyPair(pemContent: String): OperationResult<KeyPair> {
        return try {
            OperationResult.Success(org.matrix.TEESimulator.AuraKeyboxValidator.parseKeyPair(pemContent))
        } catch (e: Exception) {
            OperationResult.Error("Failed to parse private key", e)
        }
    }"""+text[end:]
    helper.write_text(text);changes.append(str(helper.relative_to(root)))
    # Disable verbose/debug logs without modifying the protocol or cryptographic implementation.
    logger=root/'app/src/main/java/org/matrix/TEESimulator/logging/SystemLogger.kt'
    text=logger.read_text().replace('Log.d(TAG, message)','Unit').replace('Log.v(TAG, message)','Unit')
    logger.write_text(text);changes.append(str(logger.relative_to(root)))
    java=root/'app/src/main/java/org/matrix/TEESimulator';java.mkdir(parents=True,exist_ok=True)
    shutil.copy(BASE/'integration/AuraKeyboxValidator.java',java/'AuraKeyboxValidator.java')
    tests=root/'app/src/test/java/org/matrix/TEESimulator';tests.mkdir(parents=True,exist_ok=True)
    shutil.copy(BASE/'integration/AuraKeyboxValidatorTest.java',tests/'AuraKeyboxValidatorTest.java')
    rules=root/'app/proguard-rules.pro'
    with rules.open('a') as out:out.write('\n-keep class org.matrix.TEESimulator.AuraKeyboxValidator { public static void main(java.lang.String[]); }\n')
    gradle=root/'app/build.gradle.kts';text=gradle.read_text()
    text=text.replace('    defaultConfig {','    defaultConfig {\n        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }',1)
    text=text.replace('dependencies {','dependencies {\n    testImplementation("junit:junit:4.13.2")\n    testImplementation("org.json:json:20240303")',1)
    # XML APIs are part of Android; regular JVM tests use the host JAXP implementation.
    gradle.write_text(text)
    with urllib.request.urlopen('https://android.googleapis.com/attestation/root',timeout=20) as response: data=response.read(262145)
    assert len(data)<=262144,'Oversized public root list'
    roots=json.loads(data)
    assert isinstance(roots,list) and 1<=len(roots)<=32
    assert all(isinstance(r,str) and r.startswith('-----BEGIN CERTIFICATE-----') for r in roots)
    (root/'module/roots.json').write_bytes(data)
    provenance={'upstream':'https://github.com/JingMatrix/TEESimulator','commit':UPSTREAM,'lsplt_commit':LSPLT,
        'public_roots_sha256':hashlib.sha256(data).hexdigest(),'public_root_count':len(roots),
        'changes':changes+['app/proguard-rules.pro','app/build.gradle.kts','app/src/main/java/org/matrix/TEESimulator/AuraKeyboxValidator.java','module/*'],
        'private_keybox_bundled':False,'strong_integrity_verified':False}
    (root/'module/PROVENANCE.json').write_text(json.dumps(provenance,indent=2)+'\n')
    shutil.copy(root/'LICENSE',root/'module/LICENSE')
    print(json.dumps(provenance,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();prepare(a.root.resolve())
