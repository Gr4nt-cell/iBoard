#!/usr/bin/env python3
import argparse, hashlib, json, re, shutil, subprocess, zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

BASE=Path(__file__).resolve().parents[1]
def package(root,out):
    out.mkdir(parents=True,exist_ok=True)
    candidates=list((root/'out').glob('*Release*.zip'));assert len(candidates)==1,candidates
    target=out/'AURA-Integrity-Candidate-1.0.zip';shutil.copy(candidates[0],target)
    # Redistribute runtime dependency notices alongside the binary, not only in source.
    notices={}
    external=root/'app/src/main/cpp/external'
    for path in external.rglob('*'):
        if path.is_file() and path.name.upper().startswith(('LICENSE','COPYING','NOTICE')) and path.stat().st_size<262144:
            notices['THIRD-PARTY/'+str(path.relative_to(external))]=path.read_bytes()
    cache=Path.home()/'.gradle/caches/modules-2/files-2.1/org.bouncycastle'
    for jar in cache.rglob('*.jar'):
        with zipfile.ZipFile(jar) as dependency:
            if 'META-INF/LICENSE.md' in dependency.namelist():
                notices['THIRD-PARTY/BouncyCastle-LICENSE.md']=dependency.read('META-INF/LICENSE.md')
                break
    assert 'THIRD-PARTY/BouncyCastle-LICENSE.md' in notices,'Missing BouncyCastle runtime notice'
    with zipfile.ZipFile(target,'a',zipfile.ZIP_DEFLATED) as z:
        for name,data in sorted(notices.items()):z.writestr(name,data)
    with zipfile.ZipFile(target) as z:
        assert z.testzip() is None
        members=z.namelist()
        assert not any('keybox' in name.lower() for name in members),'Private keybox must not be bundled'
        for name in ['classes.dex','customize.sh','service.sh','launch.sh','action.sh','roots.json','PROVENANCE.json','README.md']:
            assert name in members,name
        assert z.read('classes.dex').startswith(b'dex\n')
        for abi in ['arm64-v8a','armeabi-v7a','x86_64','x86']:
            for lib in ['libinject.so','libTEESimulator.so']:
                data=z.read(f'lib/{abi}/{lib}');assert data[:4]==b'\x7fELF',(abi,lib)
                assert b'libc++_shared.so' not in data,'Runtime must be self-contained'
        props=z.read('module.prop').decode();assert 'id=aura_integrity' in props
    source=out/'AURA-Integrity-Candidate-1.0-source.zip'
    with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as z:
        for path in root.rglob('*'):
            if not path.is_file():continue
            rel=path.relative_to(root)
            if any(part in {'.git','.gradle','.cxx','build','out'} for part in rel.parts):continue
            if path.name=='local.properties' or (path.suffix.lower()=='.xml' and 'keybox' in path.name.lower()):continue
            z.write(path,Path('TEESimulator')/rel)
        for path in BASE.rglob('*'):
            if path.is_file() and '__pycache__' not in path.parts:z.write(path,Path('aura-integration')/path.relative_to(BASE))
    results=list((root/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
    counts={'tests':0,'failures':0,'errors':0,'skipped':0}
    for result in results:
        suite=ET.parse(result).getroot()
        for key in counts:counts[key]+=int(suite.get(key,'0'))
    assert counts['tests']>=8 and counts['failures']==0 and counts['errors']==0 and counts['skipped']==0,counts
    report={'credential_unit_tests':counts,'private_keybox_bundled':False,'abis':['arm64-v8a','armeabi-v7a','x86_64','x86'],
            'actual_google_verdict':'UNTESTED; authorized external credentials and a live device are required',
            'upstream':json.loads((root/'module/PROVENANCE.json').read_text()),'sha256':{}}
    for file in [target,source]:report['sha256'][file.name]=hashlib.sha256(file.read_bytes()).hexdigest()
    (out/'build-report.json').write_text(json.dumps(report,indent=2)+'\n')
    (out/'SHA256SUMS.txt').write_text(''.join(f'{value}  {name}\n' for name,value in report['sha256'].items()))
    shutil.copy(BASE/'module/README.md',out/'README.md')
    print(json.dumps(report,indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('out',type=Path);a=p.parse_args();package(a.root.resolve(),a.out.resolve())
