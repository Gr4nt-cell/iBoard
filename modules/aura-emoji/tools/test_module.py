import shutil, subprocess, tempfile, unittest, xml.etree.ElementTree as ET
from pathlib import Path
BASE=Path(__file__).resolve().parents[1]

class ModuleTests(unittest.TestCase):
    def patch(self,text):
        result=subprocess.run(['awk','-f',str(BASE/'patch-config.awk')],input=text,text=True,capture_output=True)
        self.assertEqual(result.returncode,0,result.stderr)
        ET.fromstring(result.stdout)
        return result.stdout
    def test_both_formats_preserve_locale_and_emoji_fallback(self):
        for family in ['<family lang="und-Zsye"><font>NotoColorEmoji.ttf</font></family>',"<family\n lang='und-Zsye' variant='compact'><font>NotoColorEmoji.ttf</font></family>"]:
            original='<familyset><family name="sans-serif"><font>Roboto.ttf</font></family><family lang="ja"><font index="2">NotoCJK.ttc</font></family>'+family+'<alias name="sans" to="sans-serif"/></familyset>'
            out=self.patch(original)
            self.assertLess(out.index('AuraEmojiSystem.ttf'),out.index('NotoColorEmoji.ttf'))
            self.assertIn('<font index="2">NotoCJK.ttc</font>',out)
            self.assertIn('<alias name="sans" to="sans-serif"/>',out)
            self.assertEqual(len(ET.fromstring(out).findall('family')),4)
    def test_comments_are_not_mistaken_for_families(self):
        out=self.patch('<!-- <family lang="und-Zsye">example</family> --><familyset><family name="sans"><font>Roboto.ttf</font></family><family lang="und-Zsye"><font>NotoColorEmoji.ttf</font></family></familyset>')
        self.assertEqual(len(ET.fromstring(out).findall('family')),3)
    def test_idempotent(self):
        original='<familyset><family name="sans"><font>Roboto.ttf</font></family><family><font>NotoColorEmoji.ttf</font></family></familyset>'
        once=self.patch(original);twice=self.patch(once)
        self.assertEqual(once,twice)
    def test_unsupported_config_does_not_replace_stock_xml(self):
        p=subprocess.run(['awk','-f',str(BASE/'patch-config.awk')],input='<fonts-modification/>',text=True,capture_output=True)
        self.assertNotEqual(p.returncode,0)
        self.assertEqual(p.stdout,'')
    def test_installer_regenerates_current_rom_and_preserves_stock(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'root';mod=Path(tmp)/'module';mod.mkdir()
            for name in ['apply-fonts.sh','patch-config.awk']: shutil.copy(BASE/name,mod/name)
            (mod/'system/fonts').mkdir(parents=True)
            (mod/'system/fonts/AuraEmojiSystem.ttf').write_bytes(b'aura')
            (root/'system/etc').mkdir(parents=True);(root/'system/fonts').mkdir(parents=True)
            stock='<familyset><family name="sans"><font>Roboto.ttf</font></family><family lang="und-Zsye"><font>NotoColorEmoji.ttf</font></family></familyset>'
            (root/'system/etc/fonts.xml').write_text(stock)
            (root/'system/fonts/NotoColorEmoji.ttf').write_bytes(b'stock')
            (root/'system/fonts/SamsungColorEmoji.ttf').write_bytes(b'oem')
            (root/'product/fonts').mkdir(parents=True)
            (root/'product/fonts/ColorOS_Emoji.ttf').write_bytes(b'coloros')
            for _ in range(2): subprocess.run(['sh',str(mod/'apply-fonts.sh'),str(mod),str(root)],check=True)
            self.assertEqual((root/'system/etc/fonts.xml').read_text(),stock)
            self.assertEqual((root/'system/fonts/NotoColorEmoji.ttf').read_bytes(),b'stock')
            self.assertFalse((mod/'system/fonts/NotoColorEmoji.ttf').exists())
            self.assertEqual((mod/'system/fonts/SamsungColorEmoji.ttf').read_bytes(),b'aura')
            self.assertEqual((mod/'system/product/fonts/ColorOS_Emoji.ttf').read_bytes(),b'aura')
            # An OTA changes the stock config; regenerated overlay must include the new font.
            (root/'system/etc/font_fallback.xml').write_text(stock.replace('Roboto.ttf','RobotoVF.ttf'))
            subprocess.run(['sh',str(mod/'apply-fonts.sh'),str(mod),str(root)],check=True)
            self.assertIn('RobotoVF.ttf',(mod/'system/etc/font_fallback.xml').read_text())
    def test_shell_syntax(self):
        for path in list(BASE.glob('*.sh'))+[BASE/'META-INF/com/google/android/update-binary']:
            subprocess.run(['sh','-n',str(path)],check=True)

if __name__=='__main__': unittest.main()
