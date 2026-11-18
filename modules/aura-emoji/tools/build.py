#!/usr/bin/env python3
import argparse, hashlib, json, re, tempfile, zipfile
from pathlib import Path
from fontTools.ttLib import TTFont
from font_check import check

BASE=Path(__file__).resolve().parents[1]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def build(source, output, legacy=None):
    identity='aura_emoji';old={};members=[];scripts={}
    if legacy:
        with zipfile.ZipFile(legacy) as z:
            members=z.namelist()
            props=next(n for n in members if n=='module.prop' or n.endswith('/module.prop'))
            old=dict(line.split('=',1) for line in z.read(props).decode().splitlines() if '=' in line and not line.lstrip().startswith('#'))
            identity=old['id'].strip()
            for name in members:
                if name.endswith(('.sh','module.prop')) and z.getinfo(name).file_size<64000:
                    scripts[name]=z.read(name).decode(errors='replace')
    assert re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]*',identity),identity
    output.mkdir(parents=True,exist_ok=True)
    report={'source_font_sha256':sha(source),'legacy_id':identity,'legacy_properties':old,'legacy_members':members,'legacy_scripts':scripts}
    with tempfile.TemporaryDirectory() as temp:
        font=TTFont(source,recalcTimestamp=False)
        order=font.getGlyphOrder()
        for record in font['name'].names:
            values={1:'AURA Emoji System',3:'AURA Emoji System 4.0',4:'AURA Emoji System',6:'AuraEmojiSystem-Regular'}
            if record.nameID in values:
                record.string=values[record.nameID].encode(record.getEncoding())
        font['head'].fontRevision=4.0
        payload=Path(temp)/'AuraEmojiSystem.ttf';font.save(payload)
        assert TTFont(payload).getGlyphOrder()==order,'Glyph IDs changed'
        report['font_validation']=check(payload)
        report['payload_sha256']=sha(payload)
        prop=f'id={identity}\nname=AURA Emoji System 4.0\nversion=4.0.0\nversionCode={max(40000,int(old.get("versionCode",0))+1)}\nauthor=gr4nt\ndescription=System emoji fallback and OEM aliases. Bundled/app-image emoji may differ.\n'
        files={p.relative_to(BASE).as_posix():p.read_bytes() for p in BASE.rglob('*') if p.is_file() and 'tools' not in p.relative_to(BASE).parts and '__pycache__' not in p.parts}
        files['module.prop']=prop.encode();files['payload/AuraEmojiSystem.ttf']=payload.read_bytes()
        files['FONT-LICENSE.txt']='\n\n'.join(str(font['name'].getDebugName(i) or '') for i in [0,13,14]).encode()
        archive=output/'AURA-Emoji-Magisk-4.0.zip'
        with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
            for name,data in sorted(files.items()):
                info=zipfile.ZipInfo(name,(2026,11,18,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                info.external_attr=((0o100755 if name.endswith('.sh') or name.endswith('update-binary') else 0o100644)<<16)
                z.writestr(info,data)
        with zipfile.ZipFile(archive) as z: assert z.testzip() is None
    report['module_sha256']=sha(archive)
    (output/'build-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    (output/'SHA256SUMS.txt').write_text(f'{sha(archive)}  {archive.name}\n')
    print(json.dumps({k:report[k] for k in ['legacy_id','font_validation','module_sha256']},ensure_ascii=False,indent=2))
    return archive
if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--font',type=Path,required=True);a.add_argument('--output',type=Path,required=True);a.add_argument('--legacy',type=Path)
    o=a.parse_args();build(o.font,o.output,o.legacy)
