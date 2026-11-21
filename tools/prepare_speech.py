"""Build-time checkout of the pinned whisper.cpp runtime. Voice models are downloaded in the app."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[1];dest=root/'app/src/main/cpp/whisper.cpp'
commit='a8d002cfd879315632a579e73f0148d06959de36'
if not dest.exists():
 subprocess.run(['git','clone','--depth','1','--branch','v1.7.6','https://github.com/ggml-org/whisper.cpp.git',str(dest)],check=True)
assert subprocess.check_output(['git','-C',str(dest),'rev-parse','HEAD'],text=True).strip()==commit,'Unexpected speech runtime revision'
print('Pinned speech runtime verified.')
