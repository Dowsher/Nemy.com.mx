"""Numerical contract tests; synthetic signals, never training examples."""
import hashlib
import json
import sys
import time
import unittest
from pathlib import Path
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'phone/src/main/python'))
import visio_detector as ref
import visio_live as live

def fixture(duration=180):
    st, _, _, mount=ref.simulate_session(73, wrist='L',crown_toward_hand=True,duration_s=duration)
    payload={'acc': [[int(t),*a.tolist()] for t,a in zip(st.acc_t_ns,st.acc)],
             'gyro': [[int(t),*w.tolist()] for t,w in zip(st.gyr_t_ns,st.gyr)],'wrist':'L','crown_hand':True,'screen_ns':[int(t) for t in st.screen_on_t_ns]}
    return st,mount,payload

class ReferenceTests(unittest.TestCase):
    def test_source_is_unchanged(self):
        self.assertEqual(live.SOURCE_SHA256,hashlib.sha256((ROOT/'phone/src/main/python/visio_detector.py').read_bytes()).hexdigest())
    def test_closed_segments_match_reference_and_unreleased_has_no_alert_actions(self):
        st,mount,p=fixture()
        result=json.loads(live.evaluate(json.dumps(p)))
        original=ref.run_pipeline(st,ref.Config(),mount)
        self.assertGreater(len(result['gestures']),0)
        for g in result['gestures']:
            pair=next((s,f) for s,f in zip(original['segments'],original['features']) if s.t_on==g['segment']['t_on'] and s.chunk==g['segment']['chunk'])
            for key,v in g['features'].items():
                if v is not None:self.assertAlmostEqual(pair[1][key],v,places=6,msg=key)
            score,llr,_=ref.BootstrapScorer().predict(pair[1]);self.assertAlmostEqual(score,g['score'],places=10)
        self.assertFalse(result['released'])
        self.assertFalse(any(a.get('type')=='CHECKIN_PROMPT' for a in result['actions']))
        self.assertFalse(any(e['state']=='END' for e in result['episodes']))
    def test_prefix_replay_keeps_mature_features_and_missing_samples_are_not_zero_filled(self):
        _,_,p=fixture()
        prefix={**p,'acc':p['acc'][:3000],'gyro':p['gyro'][:3000]}
        early=json.loads(live.evaluate(json.dumps(prefix)));full=json.loads(live.evaluate(json.dumps(p)))
        for g in early['gestures']:
            later=next(v for v in full['gestures'] if v['segment']['t_on']==g['segment']['t_on'] and v['segment']['chunk']==g['segment']['chunk'])
            self.assertEqual(g,later)
        p['acc']=p['acc'][:500]+p['acc'][1000:]
        gap=json.loads(live.evaluate(json.dumps(p)))
        self.assertLess(gap['valid_fraction'],full['valid_fraction'])
        _, samples, ok=ref.resample_uniform(np.array([int(r[0]) for r in p['acc']]),np.array([r[1:] for r in p['acc']]),25,int(p['acc'][0][0]),int(p['acc'][-1][0]),.12)
        self.assertTrue(np.isnan(samples[~ok]).all())
        self.assertGreater((~ok).sum(),100)

if __name__=='__main__':
    if '--fixture' in sys.argv:
        folder=ROOT/'phone/src/debug/python';folder.mkdir(parents=True,exist_ok=True)
        _,_,p=fixture(600)
        started=time.perf_counter();expected=json.loads(live.evaluate(json.dumps(p)))
        data={'synthetic_test_only':True,'input':p,'expected':expected}
        (folder/'reference_fixture.json').write_text(json.dumps(data,separators=(',',':')),encoding='utf-8',newline='\n')
        print('Synthetic 10 minute fixture:',len(expected['gestures']),'gestures; desktop seconds',round(time.perf_counter()-started,3))
    else:unittest.main()
