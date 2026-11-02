"""Debug-only numerical parity probe; no hardware access, no dataset writes."""
import json
import math
import time
from pathlib import Path
import visio_live

def run():
    data=json.loads((Path(__file__).parent/'reference_fixture.json').read_text())
    started=time.perf_counter();actual=json.loads(visio_live.evaluate(json.dumps(data['input'])))
    comparisons=0
    def compare(a,b,path='root'):
        nonlocal comparisons
        if isinstance(a,dict):
            assert a.keys()==b.keys(),path
            for k in a:compare(a[k],b[k],path+'.'+k)
        elif isinstance(a,list):
            assert len(a)==len(b),path
            for i,(x,y) in enumerate(zip(a,b)):compare(x,y,path+f'[{i}]')
        elif isinstance(a,(int,float)) and not isinstance(a,bool):
            assert math.isclose(a,b,rel_tol=1e-5,abs_tol=1e-6),(path,a,b)
            comparisons+=1
        else:assert a==b,(path,a,b)
    compare(data['expected'],actual)
    return json.dumps({'passed':True,'numeric_comparisons':comparisons,'seconds':time.perf_counter()-started,
                       'gestures':len(actual['gestures']),'source_sha256':visio_live.SOURCE_SHA256,'synthetic_test_only':True})
