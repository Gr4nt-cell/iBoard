"""Exercise the production Kotlin/JNI bridge on the host, including timeout and cancel.
Requires JDK17, Gradle8.13 lib, whisper.cpp host shared build, and g++.
This tests the bridge, not Android microphone hardware.
"""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1];workspace=root.parent
jdk=Path(os.environ['JAVA_HOME']);gradle=Path(os.environ.get('IBOARD_GRADLE_LIB',workspace/'gradle-tools/gradle-8.13/lib'))
host=Path(os.environ.get('IBOARD_WHISPER_HOST',workspace/'whisper-host'))
src=root/'app/src/main/java/com/gr4nt/iboard/voice/OfflineSpeech.kt'
with tempfile.TemporaryDirectory() as temp:
 d=Path(temp)
 subprocess.run(['g++','-shared','-fPIC','-O3','-std=c++17','-pthread','-I'+str(jdk/'include'),'-I'+str(jdk/'include/linux'),'-I'+str(root/'app/src/main/cpp/whisper.cpp/include'),'-I'+str(root/'app/src/main/cpp/whisper.cpp/ggml/include'),str(root/'app/src/main/cpp/whisper_jni.cpp'),'-L'+str(host/'src'),'-lwhisper','-Wl,-rpath,'+str(host/'src'),'-o',str(d/'libiboard_whisper.so')],check=True)
 production=src.read_text().split('/**\n * Records 16 kHz')[0]
 production='\n'.join(l for l in production.splitlines() if not l.startswith('import android.'))
 (d/'WhisperLib.kt').write_text(production)
 (d/'Checks.kt').write_text('''import com.gr4nt.iboard.voice.WhisperLib
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
fun main(args:Array<String>) {
 val bytes=File(args[1]).readBytes();val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
 var at=12;var data=0;var size=0
 while(at+8<=bytes.size){val tag=String(bytes,at,4);val n=b.getInt(at+4);if(tag=="data"){data=at+8;size=n;break};at+=8+n+(n%2)}
 check(data>0);val pcm=FloatArray(size/2){b.getShort(data+it*2)/32768f}
 check(WhisperLib.available()){"Native library missing"}
 fun model()=WhisperLib.load(args[0])?:error("Bundled model failed to load")
 val loaded=model();val started=System.nanoTime()
 val text=loaded.transcribe(pcm,"en",WhisperLib.promptFor("en"))
 check(text.lowercase().contains("ask not what your country can do for you")){"Wrong transcript: $text"}
 check(loaded.progress()==100){"Completion progress missing"}
 loaded.close();loaded.close();check(loaded.transcribe(pcm,"en","")=="")
 println("PASS: production JNI transcript + completion + repeated close ($text), %.2fs".format((System.nanoTime()-started)/1e9))
 val timeout=model();val timeStart=System.nanoTime()
 try {timeout.transcribe(pcm,"en","",1);error("Timeout was ignored")} catch(expected:TimeoutException) {}
 finally{timeout.close()}
 check((System.nanoTime()-timeStart)/1e9<10){"Timeout failed to return"}
 println("PASS: native decoding deadline exits with TimeoutException")
 val cancelled=model();val pool=Executors.newSingleThreadExecutor()
 val run=pool.submit<String>{cancelled.transcribe(pcm,"en","")}
 Thread.sleep(50);val cancelStart=System.nanoTime();cancelled.cancel();cancelled.close()
 check(run.get(10,TimeUnit.SECONDS).isEmpty()){"Cancelled transcript escaped"}
 check((System.nanoTime()-cancelStart)/1e9<10){"Cancellation failed to return"}
 check(cancelled.progress()==0);pool.shutdownNow()
 println("PASS: concurrent cancel/close aborts JNI without a stale transcript or crash")
}
''')
 cp=':'.join(map(str,gradle.glob('*.jar')));std=next(gradle.glob('kotlin-stdlib-*.jar'))
 subprocess.run([str(jdk/'bin/java'),'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(std),'-d',str(d/'checks.jar'),str(d/'WhisperLib.kt'),str(d/'Checks.kt')],check=True)
 env=os.environ.copy();env['LD_LIBRARY_PATH']=':'.join([str(host/'src'),str(host/'ggml/src'),env.get('LD_LIBRARY_PATH','')])
 subprocess.run([str(jdk/'bin/java'),'-Djava.library.path='+str(d),'-cp',str(d/'checks.jar')+':'+str(std),'ChecksKt',str(root/'app/src/main/assets/models/ggml-base-q5_1.bin'),str(root/'app/src/main/cpp/whisper.cpp/samples/jfk.wav')],check=True,env=env,timeout=60)
