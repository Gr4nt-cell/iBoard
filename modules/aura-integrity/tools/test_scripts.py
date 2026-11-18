import pathlib,subprocess,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class Scripts(unittest.TestCase):
    def test_shell_syntax(self):
        for path in list((ROOT/'module').glob('*.sh'))+[ROOT/'module/daemon',ROOT/'module/META-INF/com/google/android/update-binary']:
            subprocess.run(['sh','-n',str(path)],check=True)
    def test_no_private_key_or_fetcher_packaged_in_sources(self):
        self.assertFalse(any('keybox' in path.name.lower() for path in (ROOT/'module').rglob('*')))
        for path in (ROOT/'module').glob('*.sh'):
            text=path.read_text()
            self.assertNotIn('curl ',text)
            self.assertNotIn('wget ',text)
            self.assertNotIn('resetprop',text)
    def test_no_existing_credentials_or_app_data_touched(self):
        for path in (ROOT/'module').glob('*.sh'):
            text=path.read_text()
            self.assertNotIn('/data/adb/tricky_store',text)
            self.assertNotIn('pm clear',text)
            self.assertNotIn('setenforce',text)
            self.assertNotIn('killall',text)
if __name__=='__main__':unittest.main()
