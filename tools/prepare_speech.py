"""Build-time runtime checkout and packaged-model integrity check. No app networking."""
from pathlib import Path
import hashlib,subprocess
root=Path(__file__).resolve().parents[1];dest=root/'app/src/main/cpp/whisper.cpp'
commit='a8d002cfd879315632a579e73f0148d06959de36'
if not dest.exists():
 subprocess.run(['git','clone','--depth','1','--branch','v1.7.6','https://github.com/ggml-org/whisper.cpp.git',str(dest)],check=True)
assert subprocess.check_output(['git','-C',str(dest),'rev-parse','HEAD'],text=True).strip()==commit,'Unexpected speech runtime revision'
assets=root/'app/src/main/assets/models';model=assets/'ggml-base-q5_1.bin'
assert hashlib.sha256(model.read_bytes()).hexdigest()==(assets/'speech.sha256').read_text().split()[0],'Speech model checksum mismatch'
print('Pinned speech runtime and bundled model verified.')
