import tempfile
import unittest
import wave
import array
from pathlib import Path
from zoom_capture import inspect_wave, save_json
import json

class AudioIntegrityTest(unittest.TestCase):
    def test_frames_quality_hash_and_atomic_annotations(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'test.wav'
            with wave.open(str(path),'wb') as out:
                out.setnchannels(1);out.setsampwidth(2);out.setframerate(48000)
                out.writeframes(array.array('h',[0,32767,-32768,100]*12000).tobytes())
            result=inspect_wave(path)
            self.assertEqual(result['frames'],48000)
            self.assertEqual(result['duration_seconds'],1)
            self.assertEqual(result['clipped_samples'],24000)
            self.assertEqual(len(result['sha256']),64)
            manifest=Path(folder)/'session.json'
            save_json(manifest,{'review':'unreviewed','markers':[{'label':'referencia'}]})
            self.assertEqual(json.loads(manifest.read_text())['review'],'unreviewed')
            self.assertFalse(manifest.with_suffix('.tmp').exists())
            path.write_bytes(path.read_bytes()[:-100])
            with self.assertRaises(ValueError): inspect_wave(path)

    def test_empty_audio_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'test.wav'
            with wave.open(str(path),'wb') as out:
                out.setnchannels(1);out.setsampwidth(2);out.setframerate(48000)
            with self.assertRaises(ValueError): inspect_wave(path)

if __name__=='__main__':unittest.main()
