"""Index completed local Zoom recordings without changing original audio or human labels."""
import json
from pathlib import Path
import datetime
from zoom_capture import inspect_wave, save_json

def build(root:Path):
    entries=[]
    for file in sorted((root/'capturas').glob('*/session.json')):
        meta=json.loads(file.read_text(encoding='utf-8'))
        if meta['status']=='recording':continue
        entry={'metadata':str(file.relative_to(root)), 'audio':str((file.parent/'audio.wav').relative_to(root)),
               'id':meta['id'],'activity':meta['activity'],'review':meta['review'],'status':meta['status']}
        try:
            actual=inspect_wave(file.parent/'audio.wav')
            entry['quality']=actual
            entry['hash_matches_original']=actual['sha256']==meta.get('audio',{}).get('sha256')
            entry['clipped_fraction']=actual['clipped_samples']/actual['frames']
        except Exception as exc:entry['error']=str(exc)
        entries.append(entry)
    result={'schema':'visio-zoom-index-v1','created_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'note':'Human session labels. Review individual sounds and quality. Zoom examples are not automatically a validated watch sound model.',
            'recordings':entries}
    save_json(root/'INDICE-DATOS.json',result)
    return result

if __name__=='__main__':
    result=build(Path.home()/'Downloads/Visio-Captura-Zoom')
    print(f"Indexed {len(result['recordings'])} completed recordings; originals preserved.")
