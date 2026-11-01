"""Run production prediction and geometry logic on the JVM with file-backed Android context stubs."""
from pathlib import Path
import tempfile,subprocess,os,json
root=Path(__file__).resolve().parents[1];src=root/'app/src/main/java/com/gr4nt/iboard'
gradle=Path(os.environ.get('IBOARD_GRADLE_LIB','/workspace/scratch/a4ef28febb04/gradle-tools/gradle-8.13/lib'))
if not gradle.exists():raise SystemExit('Set IBOARD_GRADLE_LIB to the lib directory of Gradle 8.13.')
cp=':'.join(str(p) for p in gradle.glob('*.jar'));std=next(gradle.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp)
 (d/'Context.kt').write_text('''package android.content
import java.io.File
class Assets(private val root:File){ fun open(path:String)=File(root,path).inputStream() }
class Context(val filesDir:File, root:File){val assets=Assets(root)}
''')
 (d/'EditorInfo.kt').write_text('''package android.view.inputmethod
object EditorInfo { const val IME_ACTION_GO=2;const val IME_ACTION_SEARCH=3;const val IME_ACTION_SEND=4;const val IME_ACTION_NEXT=5;const val IME_ACTION_DONE=6 }
''')
 (d/'Checks.kt').write_text(r'''import com.gr4nt.iboard.*
import com.gr4nt.iboard.predict.*
import android.content.Context
import java.io.File
fun main(args:Array<String>) {
 val context=Context(File(args[1]).apply{mkdirs()},File(args[0]));var count=0
 fun verify(ok:Boolean,message:String){ check(ok){message};count++ }
 val dict=Dictionary.load(context,"en");val user=UserModel(context,"en")
 val suggester=Suggester(dict,user,Suggester.neighborsFor(Layouts.letterRows.getValue("en")))
 verify(suggester.forWord("",null,true).items.isNotEmpty(),"Empty input")
 verify(suggester.forWord("i",null,true).autoCorrect=="I","English I")
 for((typo,word) in listOf("teh" to "the","recieve" to "receive","becuase" to "because","wrold" to "world","thnaks" to "thanks","ello" to "hello","langauge" to "language")) {
  val r=suggester.forWord(typo,null,true)
  verify(r.items.take(3).any { it.text.equals(word,true) },"$typo => ${r.items.map{it.text}} (expected $word)")
 }
 verify(suggester.forWord("hello",null,true).autoCorrect==null,"Do not replace valid words")
 verify(suggester.forWord("Abena","hello",true).autoCorrect==null,"Keep names")
 verify(suggester.forWord("123",null,true).autoCorrect==null,"Keep digits")
 verify(suggester.forWord("teh",null,false).autoCorrect==null,"Respect autocorrect setting")
 verify(suggester.nextWords("thank",false).first().text=="you","Built-in English context")
 for((lang,pair) in mapOf("es" to ("muchas" to "gracias"),"fr" to ("merci" to "beaucoup"),"pt" to ("bom" to "dia"),"de" to ("guten" to "morgen"),"ar" to ("صباح" to "الخير"))) {
  val s=Suggester(Dictionary.load(context,lang),UserModel(context,lang),Suggester.neighborsFor(Layouts.letterRows.getValue(lang)))
  verify(s.nextWords(pair.first,false).first().text==pair.second,"Context $lang")
 }
 user.learn("Codexify",null);user.learn("Codexify",null)
 verify(suggester.forWord("Codexify",null,true).autoCorrect==null,"Keep learned words")
 repeat(3){user.learn("book","would","i")};repeat(5){user.learn("take","would","you")}
 verify(user.nextWords("would",3,"i").first().first=="book","Trigram context")
 user.save();val reload=UserModel(context,"en")
 verify(reload.trigramCount("i","would","book")==3,"Persist trigram")
 reload.forget("book");verify(reload.nextWords("would",6,"i").none{it.first=="book"},"Forget context")
 reload.clear();verify(!File(context.filesDir,"user_en.tsv").exists(),"Reset learned data")
 var layouts=0
 for(lang in PrefLangs) for(page in Page.values()) for(number in listOf(false,true)) for(utility in listOf(false,true)) {
  val layout=Layouts.build(lang,page,number,FieldKind.TEXT,true,utility)
  verify(layout.rows.flatMap{it.keys}.any{it.type==KeyType.MIC},"Microphone accessible $lang/$page/$utility")
  verify(layout.rows.flatMap{it.keys}.any{it.type==KeyType.GLOBE},"Language control accessible $lang/$page/$utility")
  verify(layout.rows.flatMap{it.keys}.any{it.type==KeyType.BACKSPACE},"Backspace $lang/$page")
  verify(layout.rows.flatMap{it.keys}.any{it.type==KeyType.RETURN},"Return $lang/$page")
  if(page==Page.NUMBER_PAD) verify(layout.rows.flatMap{it.keys}.filter{it.type==KeyType.CHAR&&it.label.singleOrNull()?.isDigit()==true}.map{it.label}.toSet()==('0'..'9').map{it.toString()}.toSet(),"Number pad digits")
  for(dp in listOf(320f,390f,600f,768f,1024f,1366f)) for(density in listOf(1f,2f)) for(split in listOf(false,true)) for(row in layout.rows) {
   val width=dp*density;val bounds=KeyGeometry.row(row.keys,width,density,split,layout.unitsPerRow,row.isUtility)
   verify(bounds.all{it.left>=-0.1f&&it.right<=width+0.1f&&it.right>it.left},"Bounds $lang/$page/$dp/$split")
   verify(bounds.zipWithNext().all{(a,b)->a.right<=b.left+0.1f},"No overlapping keys")
   if(split) {val gap=KeyGeometry.centerGap(width,density);verify(bounds.none{it.left<width/2+gap/2-0.1f&&it.right>width/2-gap/2+0.1f},"Inactive gutter")}
   if(row.isUtility) {
    val panels=KeyGeometry.utilityPanels(bounds,width,split)
    verify(panels.size==if(split)2 else 1,"Shared utility capsule count")
    verify(panels.all{it.first>=16*density&&it.second<=width-16*density},"Capsule inset from screen edges")
    verify(bounds.all{it.right-it.left<=44*density+.1f},"Compact utility control width")
    if(!split&&row.keys.size==5)verify(kotlin.math.abs(panels.first().second-panels.first().first-252*density)<.2f,"Five evenly spaced icons in compact capsule")
    verify(bounds.all{b->panels.any{it.first<=b.left+.1f&&it.second>=b.right-.1f}},"Every utility control inside its capsule")
    if(split){val gap=KeyGeometry.centerGap(width,density);verify(panels.none{it.first<width/2+gap/2-.1f&&it.second>width/2-gap/2+.1f},"Capsule leaves landscape gutter empty")}
    val globe=bounds.firstOrNull{it.key.type==KeyType.GLOBE};val mic=bounds.firstOrNull{it.key.type==KeyType.MIC}
    if(split&&row.keys.size>2&&globe!=null&&mic!=null)verify(panels.any{it.first<=minOf(globe.left,mic.left)+.1f&&it.second>=maxOf(globe.right,mic.right)-.1f},"Mic beside globe in one split capsule")
   }
   if(split&&row.keys.any{it.type==KeyType.SPACE})verify(bounds.count{it.key.type==KeyType.SPACE}==2,"Two thumb space bars")
  }
  layouts++
 }
 val contextI=dict.contextModel.predict("would","i")
 val contextHe=dict.contextModel.predict("would","he")
 verify(contextI.first().first=="like","Bundled I would context")
 verify(contextHe.first().first=="be","Bundled he would context")
 verify(suggester.nextWords("would",false,"i").take(3).any{it.text=="like"},"Trained next word integrated")
 verify(suggester.forWord("li","would",false,false,"i").items.take(3).any{it.text=="like"},"Contextual completion integrated")
 for(lang in PrefLangs){
  val model=ContextModel.load(context,lang)
  val file=File(args[0],"prediction/$lang.tsv")
  verify(file.length()>500000,"Embedded model $lang")
 }
 val words=listOf("teh","wrold","becuase","hellp","recieve","langauge")
 val start=System.nanoTime();repeat(10){words.forEach{suggester.forWord(it,"the",true)}}
 val ms=(System.nanoTime()-start)/1_000_000.0/60
 val snapshots=ArrayList<String>()
 fun quote(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\""
 fun snapshot(name:String,width:Float,split:Boolean,page:Page,lang:String="en",compact:Boolean=false) {
  val l=Layouts.build(lang,page,true,FieldKind.TEXT,true,true);val keys=ArrayList<String>();val panels=ArrayList<String>();val gap=if(compact)6f else 10f
  var y=if(compact)42f else 52f
  for(row in l.rows){val h=if(row.isUtility)43.6f else if(row.isNumberRow) (if(compact)29.64f else 38f) else (if(compact)35.1f else 45f)
   if(row.isUtility)y-=gap/2
   val placed=KeyGeometry.row(row.keys,width,1f,split,l.unitsPerRow,row.isUtility)
   if(row.isUtility)for((left,right) in KeyGeometry.utilityPanels(placed,width,split))panels+="{\"x\":"+left+",\"y\":"+(y+3)+",\"w\":"+(right-left)+",\"h\":"+(h-6)+"}"
   for(b in placed)keys+="{\"type\":"+quote(b.key.type.name)+",\"label\":"+quote(b.key.label)+",\"x\":"+b.left+",\"y\":"+y+",\"w\":"+(b.right-b.left)+",\"h\":"+h+",\"utility\":"+row.isUtility+"}"
   y+=h+gap
  }
  snapshots+="{\"name\":"+quote(name)+",\"width\":"+width+",\"height\":"+(y-gap+6)+",\"split\":"+split+",\"keys\":["+keys.joinToString(",")+"],\"capsules\":["+panels.joinToString(",")+"]}"
 }
 snapshot("Portrait",390f,false,Page.LETTERS);snapshot("Number pad",390f,false,Page.NUMBER_PAD)
 snapshot("Tablet landscape",1180f,true,Page.LETTERS);snapshot("Tablet portrait",768f,false,Page.LETTERS)
 snapshot("Phone landscape",844f,true,Page.LETTERS,compact=true)
 File(args[2]).writeText("["+snapshots.joinToString(",")+"]")
 println("PASS: $count prediction, persistence and geometry checks across $layouts layouts. Warm prediction mean: %.2f ms".format(ms))
}
val PrefLangs=listOf("en","es","fr","pt","de","ar")
''')
 files=[src/'Layouts.kt',src/'KeyGeometry.kt',src/'predict/ContextModel.kt',src/'predict/Dictionary.kt',src/'predict/UserModel.kt',src/'predict/Suggester.kt',d/'Context.kt',d/'EditorInfo.kt',d/'Checks.kt']
 subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(std),'-d',str(d/'checks.jar'),*map(str,files)],check=True)
 subprocess.run(['java','-cp',str(d/'checks.jar')+':'+str(std),'ChecksKt',str(root/'app/src/main/assets'),str(d/'user'),str(root/'design/layouts.json')],check=True)
