# Make the AURA font handle emoji written with variation selectors (U+FE0F/U+FE0E):
# a zero-width glyph for both, ligatures for every fully-qualified sequence, and a cmap
# format-14 table, so Android keeps sequences like 👨‍✈️ or 🙂‍↔️ as one emoji.
import sys,re
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._c_m_a_p import CmapSubtable
from fontTools.ttLib.tables import otTables
src,dst,test=sys.argv[1],sys.argv[2],sys.argv[3]
f=TTFont(src)
order=f.getGlyphOrder()
VS='uniFE0F'
if VS not in order:
    f.setGlyphOrder(order+[VS]); f['hmtx'].metrics[VS]=(0,0)
    if 'vmtx' in f: f['vmtx'].metrics[VS]=(0,0)
    if 'post' in f and hasattr(f['post'],'extraNames'): pass
cm=f.getBestCmap()
for t in f['cmap'].tables:
    if t.format in (4,12): t.cmap[0xFE0F]=VS; t.cmap[0xFE0E]=VS
cm=f.getBestCmap()
# existing ligatures: components(without FE0F) -> glyph
g=f['GSUB'].table;lk=g.LookupList.Lookup[0];st=lk.SubTable[0]
if lk.LookupType==7: st=st.ExtSubTable
ligs=st.ligatures
known={}
for first,L in ligs.items():
    for l in L: known[(first,)+tuple(l.Component)]=l.LigGlyph
def glyphs(cps): 
    r=[cm.get(c) for c in cps]
    return None if None in r else tuple(r)
added=0;bases=set()
for line in open(test,encoding='utf8'):
    m=re.match(r'([0-9A-F ]+)\s*;\s*(fully|minimally)-qualified',line)
    if not m: continue
    cps=[int(c,16) for c in m.group(1).split()]
    if 0xFE0F not in cps: continue
    for i,c in enumerate(cps):
        if c==0xFE0F and i>0: bases.add(cps[i-1])
    bare=[c for c in cps if c!=0xFE0F]
    gb=glyphs(bare)
    if gb is None: continue
    target=known.get(gb) if len(gb)>1 else gb[0]
    if target is None: continue
    gf=glyphs(cps)
    if gf in known: continue
    l=otTables.Ligature(); l.Component=list(gf[1:]); l.LigGlyph=target; l.CompCount=len(gf)
    ligs.setdefault(gf[0],[]).append(l); known[gf]=target; added+=1
for first in ligs: ligs[first].sort(key=lambda l:-len(l.Component))
# cmap 14: FE0F (emoji) and FE0E (text) default variation sequences for every base in the font
t14=CmapSubtable.newSubtable(14); t14.platformID=0; t14.platEncID=5; t14.language=0; t14.cmap={}
t14.uvsDict={0xFE0F:sorted((b,None) for b in bases if b in cm)}
f['cmap'].tables.append(t14)
f.save(dst); print('added ligatures',added,'FE0F bases',len(t14.uvsDict[0xFE0F]))
