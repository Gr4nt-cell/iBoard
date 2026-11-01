"""Build an embedded interpolated trigram model from attributed Tatoeba sentences.
Build-time only. The Android application never contacts these URLs.
"""
from pathlib import Path
from collections import Counter,defaultdict
import urllib.request,bz2,io,re,json,hashlib,concurrent.futures,math
root=Path(__file__).resolve().parents[1];out=root/'app/src/main/assets/prediction';out.mkdir(parents=True,exist_ok=True)
def train(pair):
 lang,code=pair;url=f'https://downloads.tatoeba.org/exports/per_language/{code}/{code}_sentences.tsv.bz2'
 bi=Counter();tri=Counter();uni=Counter();held=[];seen=0;digest=hashlib.sha256()
 with urllib.request.urlopen(url,timeout=120) as response:
  stream=io.TextIOWrapper(bz2.BZ2File(response),encoding='utf-8')
  for line in stream:
   digest.update(line.encode());parts=line.rstrip().split('\t',2)
   if len(parts)<3:continue
   words=re.findall(r"[^\W\d_]+(?:['’][^\W\d_]+)*",parts[2].lower(),re.UNICODE)
   if not 3<=len(words)<=50:continue
   words=[w.replace('’',"'") for w in words];seen+=1
   if int(parts[0])%10==0:
    if len(held)<2000:held.append(words)
   else:
    uni.update(words);bi.update(zip(words,words[1:]));tri.update(zip(words,words[1:],words[2:]))
   if seen>=150000:break
 # Bound per-context fanout and total memory while retaining actual counts.
 def prune(counter,limit,fanout):
  groups=defaultdict(list)
  for key,n in counter.most_common():
   if n<2:break
   c=key[:-1]
   if len(groups[c])<fanout:groups[c].append((key,n))
  return sorted((x for rows in groups.values() for x in rows),key=lambda x:-x[1])[:limit]
 b=prune(bi,80000,16);t=prune(tri,80000,12)
 dest=out/(lang+'.tsv')
 with dest.open('w') as f:
  f.write('# iBoard corpus-trained language model v1\n')
  for key,n in b:f.write('B\t'+'\t'.join(key)+'\t'+str(n)+'\n')
  for key,n in t:f.write('T\t'+'\t'.join(key)+'\t'+str(n)+'\n')
 bg=defaultdict(Counter);tg=defaultdict(Counter)
 for k,n in b:bg[k[0]][k[-1]]=n
 for k,n in t:tg[k[:2]][k[-1]]=n
 hits=total=covered=0
 for words in held:
  for a,bw,c in zip(words,words[1:],words[2:]):
   scores=Counter({w:n/sum(bg[bw].values())*.3 for w,n in bg[bw].items()})
   if (a,bw) in tg:
    den=sum(tg[a,bw].values())
    for w,n in tg[a,bw].items():scores[w]+=.7*n/den
   if scores:covered+=1
   hits+=c in [w for w,n in scores.most_common(3)];total+=1
 report=dict(lang=lang,url=url,sentences=seen,heldout_sentences=len(held),heldout_words=total,top3_accuracy=hits/max(total,1),context_coverage=covered/max(total,1),bigrams=len(b),trigrams=len(t),corpus_prefix_sha256=digest.hexdigest(),sha256=hashlib.sha256(dest.read_bytes()).hexdigest())
 (out/(lang+'.json')).write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
if __name__=='__main__':
 with concurrent.futures.ThreadPoolExecutor(3) as ex:list(ex.map(train, [('en','eng'),('es','spa'),('fr','fra'),('pt','por'),('de','deu'),('ar','ara')]))
