"""Run production audio lifecycle logic with deterministic Android audio stubs.
Verifies stop/drain behavior; not a microphone hardware or recognition accuracy test.
"""
from pathlib import Path
import tempfile,os,subprocess
root=Path(__file__).resolve().parents[1];gradle=Path(os.environ.get('IBOARD_GRADLE_LIB',root.parent/'gradle-tools/gradle-8.13/lib'))
with tempfile.TemporaryDirectory() as temp:
 d=Path(temp)
 (d/'Annotation.kt').write_text('package android.annotation\nannotation class SuppressLint(vararg val value:String)')
 (d/'Process.kt').write_text('package android.os\nobject Process { const val THREAD_PRIORITY_AUDIO=-16;fun setThreadPriority(priority:Int){throw SecurityException("Simulated OEM restriction")} }')
 (d/'Audio.kt').write_text('''package android.media
object AudioFormat {const val CHANNEL_IN_MONO=16;const val ENCODING_PCM_16BIT=2}
object MediaRecorder {object AudioSource {const val VOICE_RECOGNITION=6}}
class AudioRecord(a:Int,b:Int,c:Int,d:Int,e:Int){
 companion object {const val STATE_INITIALIZED=1;const val READ_NON_BLOCKING=1;var source=ShortArray(0);var fail=false;var releases=0;fun getMinBufferSize(a:Int,b:Int,c:Int)=640}
 val state=STATE_INITIALIZED;private var offset=0
 fun startRecording(){}
 fun read(out:ShortArray,start:Int,size:Int,mode:Int):Int{
  check(mode==READ_NON_BLOCKING);if(fail)return -3
  val n=minOf(80,size,source.size-offset);if(n>0){source.copyInto(out,start,offset,offset+n);offset+=n};return n
 }
 fun stop(){}
 fun release(){releases++}
}
''')
 (d/'Checks.kt').write_text('''import com.gr4nt.iboard.voice.PauseChunker
import android.media.AudioRecord
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
fun main(){
 var count=0
 fun verify(ok:Boolean,message:String){check(ok){message};count++}
 // Nonblocking capture has no audio to read: stop must still finish and release once.
 repeat(20){
  AudioRecord.source=ShortArray(0);AudioRecord.fail=false;val before=AudioRecord.releases
  val finished=CountDownLatch(1);val chunks=AtomicInteger()
  val capture=PauseChunker({chunks.incrementAndGet()},{},{},{error("Unexpected error")},{finished.countDown()})
  verify(capture.start(),"Start empty capture");capture.stop()
  verify(finished.await(1,TimeUnit.SECONDS),"Empty capture stop hung")
  verify(AudioRecord.releases==before+1 && chunks.get()==0,"Release once without empty chunk")
 }
 // Partial reads represent 5 ms, not a hardcoded 20 ms; a real pause delivers one chunk.
 AudioRecord.source=ShortArray(16000){if(it<3200) 5000 else 0}
 val received=CountDownLatch(1);val finished=CountDownLatch(1);var samples=0;val chunks=AtomicInteger()
 val capture=PauseChunker({samples=it.size;chunks.incrementAndGet();received.countDown()},{},{},{error("Unexpected error")},{finished.countDown()})
 verify(capture.start(),"Start speech");verify(received.await(2,TimeUnit.SECONDS),"Pause did not emit")
 capture.stop();verify(finished.await(1,TimeUnit.SECONDS),"Speech capture stop hung")
 verify(chunks.get()==1 && samples==16000,"Partial reads distorted timing or duplicated final audio: $samples / ${chunks.get()}")
 // A short utterance is delivered when finishing even without a trailing pause.
 AudioRecord.source=ShortArray(3200){5000};val voiced=CountDownLatch(40);val shortDone=CountDownLatch(1);var shortSamples=0
 val short=PauseChunker({shortSamples=it.size},{voiced.countDown()},{},{error("Unexpected error")},{shortDone.countDown()})
 verify(short.start(),"Start short speech");verify(voiced.await(1,TimeUnit.SECONDS),"No short audio")
 short.stop();verify(shortDone.await(1,TimeUnit.SECONDS),"Short finalization hung");verify(shortSamples==3200,"Final speech lost")
 // Device read failures must close the queue path instead of leaving Finishing on screen.
 AudioRecord.fail=true;val failed=CountDownLatch(1);val error=CountDownLatch(1)
 val bad=PauseChunker({},{},{},{error.countDown()},{failed.countDown()})
 verify(bad.start(),"Start failed device");verify(error.await(1,TimeUnit.SECONDS),"Missing read error");verify(failed.await(1,TimeUnit.SECONDS),"Failed device did not finish")
 println("PASS: $count production capture lifecycle checks (partial reads, stop/drain, device errors, priority denial)")
}
''')
 cp=':'.join(map(str,gradle.glob('*.jar')));std=next(gradle.glob('kotlin-stdlib-*.jar'))
 files=[root/'app/src/main/java/com/gr4nt/iboard/voice/OfflineSpeech.kt',*d.glob('*.kt')]
 subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(std),'-d',str(d/'checks.jar'),*map(str,files)],check=True)
 subprocess.run(['java','-cp',str(d/'checks.jar')+':'+str(std),'ChecksKt'],check=True,timeout=30)
